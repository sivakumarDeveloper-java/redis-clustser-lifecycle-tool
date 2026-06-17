package redistool;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs INSIDE a container. Speaks the Redis cluster protocol directly (the
 * 10.10.0.x IPs are routable here but not from the control host). Emits a
 * tab-delimited text report the Java CLI parses.
 *
 * Usage: redis-tool node-agent {facts|seed|verify} [--keys N] [--seed-ip IP]
 */
final class NodeAgent {
    private static final int PORT = 6379;

    static int run(String[] args) {
        if (args.length == 0) {
            System.out.println("ERROR\tnode-agent: missing subcommand");
            return 2;
        }
        String sub = args[0];
        int keys = 1000;
        String seedIp = "127.0.0.1";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--keys") && i + 1 < args.length)
                keys = parseInt(args[++i], 1000);
            else if (args[i].equals("--seed-ip") && i + 1 < args.length)
                seedIp = args[++i];
        }
        try {
            return switch (sub) {
                case "facts" -> facts(seedIp);
                case "seed" -> seed(seedIp, keys);
                case "verify" -> verify(seedIp, keys);
                default -> {
                    System.out.println("ERROR\tunknown subcommand " + sub);
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.out.println("ERROR\t" + e.getMessage());
            return 2;
        }
    }

    // ---------------------------------------------------------------- RESP
    static final class Resp implements Closeable {
        final Socket sock;
        final BufferedInputStream in;
        final OutputStream out;

        Resp(String host, int port) throws IOException {
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 5000);
            sock.setSoTimeout(15000);
            in = new BufferedInputStream(sock.getInputStream(), 1 << 16);
            out = sock.getOutputStream();
        }

        void write(String... cmd) throws IOException {
            StringBuilder b = new StringBuilder();
            b.append('*').append(cmd.length).append("\r\n");
            for (String a : cmd)
                b.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        Object read() throws IOException {
            String line = readLine();
            if (line.isEmpty())
                throw new IOException("empty reply");
            char t = line.charAt(0);
            String rest = line.substring(1);
            switch (t) {
                case '+':
                    return rest;
                case '-':
                    throw new IOException(rest);
                case ':':
                    return Long.parseLong(rest);
                case '$': {
                    int n = Integer.parseInt(rest);
                    if (n < 0)
                        return null;
                    byte[] data = readN(n);
                    readN(2); // CRLF
                    return new String(data, StandardCharsets.UTF_8);
                }
                case '*': {
                    int n = Integer.parseInt(rest);
                    if (n < 0)
                        return null;
                    List<Object> arr = new ArrayList<>(n);
                    for (int i = 0; i < n; i++)
                        arr.add(read());
                    return arr;
                }
                default:
                    throw new IOException("bad RESP byte: " + t);
            }
        }

        Object cmd(String... c) throws IOException {
            write(c);
            return read();
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int prev = -1, c;
            while ((c = in.read()) != -1) {
                if (prev == '\r' && c == '\n') {
                    byte[] raw = b.toByteArray();
                    return new String(raw, 0, raw.length - 1, StandardCharsets.UTF_8); // drop trailing \r
                }
                b.write(c);
                prev = c;
            }
            throw new IOException("connection closed");
        }

        private byte[] readN(int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0)
                    throw new IOException("connection closed");
                off += r;
            }
            return buf;
        }

        public void close() {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- CRC16 slot
    static int crc16(byte[] data) {
        int crc = 0;
        for (byte bb : data) {
            crc ^= (bb & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    static int keySlot(String key) {
        String k = key;
        int i = key.indexOf('{');
        if (i >= 0) {
            int j = key.indexOf('}', i + 1);
            if (j > i + 1)
                k = key.substring(i + 1, j);
        }
        return crc16(k.getBytes(StandardCharsets.UTF_8)) % 16384;
    }

    static String keyName(int i) {
        return String.format("key:%04d", i);
    }

    static String valueFor(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h)
                sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------- slot map
    record SlotRange(int start, int end, String ip, int port) {
    }

    @SuppressWarnings("unchecked")
    static List<SlotRange> clusterSlots(Resp seed) throws IOException {
        Object reply = seed.cmd("CLUSTER", "SLOTS");
        List<SlotRange> ranges = new ArrayList<>();
        if (reply instanceof List<?> arr) {
            for (Object e : arr) {
                List<Object> entry = (List<Object>) e;
                int start = ((Long) entry.get(0)).intValue();
                int end = ((Long) entry.get(1)).intValue();
                List<Object> master = (List<Object>) entry.get(2);
                String ip = String.valueOf(master.get(0));
                int port = ((Long) master.get(1)).intValue();
                ranges.add(new SlotRange(start, end, ip, port));
            }
        }
        return ranges;
    }

    static SlotRange ownerOf(List<SlotRange> ranges, int slot) {
        for (SlotRange r : ranges)
            if (slot >= r.start() && slot <= r.end())
                return r;
        return null;
    }

    // ---------------------------------------------------------------- facts
    static int facts(String seedIp) throws IOException {
        StringBuilder out = new StringBuilder();
        try (Resp seed = new Resp(seedIp, PORT)) {
            String nodesRaw = String.valueOf(seed.cmd("CLUSTER", "NODES"));
            String infoRaw = String.valueOf(seed.cmd("CLUSTER", "INFO"));
            Map<String, String> cinfo = parseColonKv(infoRaw);
            out.append("INFO\t").append(cinfo.getOrDefault("cluster_state", "unknown").trim()).append("\n");

            List<String[]> nodes = new ArrayList<>(); // raw fields
            Map<String, String> id2ip = new HashMap<>();
            for (String line : nodesRaw.split("\n")) {
                line = line.strip();
                if (line.isEmpty())
                    continue;
                String[] p = line.split("\\s+");
                if (p.length < 8)
                    continue;
                String addr = p[1].split("@")[0];
                int colon = addr.lastIndexOf(':');
                String ip = addr.substring(0, colon);
                String port = addr.substring(colon + 1);
                List<String> flags = List.of(p[2].split(","));
                String role = flags.contains("master") ? "master" : (flags.contains("slave") ? "replica" : "?");
                String slots = p.length > 8 ? String.join(",", java.util.Arrays.copyOfRange(p, 8, p.length)) : "";
                nodes.add(new String[] { p[0], ip, port, role, p[3], p[7], slots });
                id2ip.put(p[0], ip);
            }

            for (String[] n : nodes) {
                String id = n[0], ip = n[1], port = n[2], role = n[3], masterId = n[4], link = n[5], slots = n[6];
                String version = "?", mem = "?", mlink = "", replicating = "";
                int keys = 0;
                try (Resp c = new Resp(ip, Integer.parseInt(port))) {
                    Map<String, String> kv = parseColonKv(String.valueOf(c.cmd("INFO")));
                    version = kv.getOrDefault("redis_version", "?");
                    mem = kv.getOrDefault("used_memory_human", "?");
                    mlink = kv.getOrDefault("master_link_status", "");
                    String db0 = kv.getOrDefault("db0", "");
                    if (db0.startsWith("keys="))
                        keys = parseInt(db0.substring(5).split(",")[0], 0);
                } catch (IOException ignored) {
                }
                if (role.equals("replica"))
                    replicating = id2ip.getOrDefault(masterId, "");
                out.append(String.join("\t", "NODE", id, ip, port, role, masterId, link, version, mem,
                        String.valueOf(keys), mlink, replicating, slots)).append("\n");
            }
        }
        System.out.print(out);
        return 0;
    }

    // ---------------------------------------------------------------- seed
    static int seed(String seedIp, int nKeys) throws IOException {
        try (Resp seed = new Resp(seedIp, PORT)) {
            List<SlotRange> ranges = clusterSlots(seed);
            if (ranges.isEmpty()) {
                System.out.println("ERROR\tno slots assigned; cluster not formed");
                return 1;
            }
            Map<String, Resp> pool = new HashMap<>();
            Map<String, Integer> dist = new LinkedHashMap<>();
            int failures = 0;
            final int batch = 256;
            Map<String, List<String[]>> buckets = new HashMap<>();
            try {
                for (int i = 1; i <= nKeys; i++) {
                    String k = keyName(i);
                    SlotRange r = ownerOf(ranges, keySlot(k));
                    if (r == null) {
                        failures++;
                        continue;
                    }
                    String key = r.ip() + ":" + r.port();
                    buckets.computeIfAbsent(key, x -> new ArrayList<>()).add(new String[] { k, valueFor(k) });
                    if (buckets.get(key).size() >= batch)
                        failures += flushSeed(pool, r, buckets.get(key), dist);
                }
                for (SlotRange r : ranges) {
                    String key = r.ip() + ":" + r.port();
                    List<String[]> items = buckets.get(key);
                    if (items != null && !items.isEmpty())
                        failures += flushSeed(pool, r, items, dist);
                }
            } finally {
                for (Resp c : pool.values())
                    c.close();
            }
            int inserted = dist.values().stream().mapToInt(Integer::intValue).sum();
            StringBuilder out = new StringBuilder();
            out.append(String.join("\t", "SEED", String.valueOf(nKeys), String.valueOf(inserted),
                    String.valueOf(failures))).append("\n");
            for (var e : dist.entrySet())
                out.append("DIST\t").append(e.getKey()).append("\t").append(e.getValue()).append("\n");
            System.out.print(out);
            return failures > 0 ? 1 : 0;
        }
    }

    private static int flushSeed(Map<String, Resp> pool, SlotRange r, List<String[]> items, Map<String, Integer> dist) {
        int fails = 0;
        try {
            Resp c = conn(pool, r.ip(), r.port());
            for (String[] kv : items)
                c.write("SET", kv[0], kv[1]);
            for (String[] kv : items) {
                Object reply = c.read();
                if ("OK".equals(reply))
                    dist.merge(r.ip(), 1, Integer::sum);
                else
                    fails++;
            }
        } catch (IOException e) {
            fails += items.size();
        }
        items.clear();
        return fails;
    }

    // ---------------------------------------------------------------- verify
    static int verify(String seedIp, int nKeys) throws IOException {
        try (Resp seed = new Resp(seedIp, PORT)) {
            List<SlotRange> ranges = clusterSlots(seed);
            if (ranges.isEmpty()) {
                System.out.println("ERROR\tno slots assigned; cluster not formed");
                return 1;
            }
            Map<String, Resp> pool = new HashMap<>();
            int[] missing = { 0 }, mismatched = { 0 };
            final int batch = 256;
            Map<String, List<String>> buckets = new HashMap<>();
            try {
                for (int i = 1; i <= nKeys; i++) {
                    String k = keyName(i);
                    SlotRange r = ownerOf(ranges, keySlot(k));
                    if (r == null) {
                        missing[0]++;
                        continue;
                    }
                    String key = r.ip() + ":" + r.port();
                    buckets.computeIfAbsent(key, x -> new ArrayList<>()).add(k);
                    if (buckets.get(key).size() >= batch)
                        flushVerify(pool, r, buckets.get(key), missing, mismatched);
                }
                for (SlotRange r : ranges) {
                    String key = r.ip() + ":" + r.port();
                    List<String> items = buckets.get(key);
                    if (items != null && !items.isEmpty())
                        flushVerify(pool, r, items, missing, mismatched);
                }
            } finally {
                for (Resp c : pool.values())
                    c.close();
            }
            int verified = nKeys - missing[0] - mismatched[0];
            boolean pass = missing[0] == 0 && mismatched[0] == 0;
            System.out.println(String.join("\t", "VERIFY", String.valueOf(nKeys), String.valueOf(verified),
                    String.valueOf(missing[0]), String.valueOf(mismatched[0]), String.valueOf(pass)));
            return pass ? 0 : 1;
        }
    }

    private static void flushVerify(Map<String, Resp> pool, SlotRange r, List<String> items, int[] missing,
            int[] mismatched) {
        try {
            Resp c = conn(pool, r.ip(), r.port());
            for (String k : items)
                c.write("GET", k);
            for (String k : items) {
                Object reply = c.read();
                if (reply == null)
                    missing[0]++;
                else if (!valueFor(k).equals(reply))
                    mismatched[0]++;
            }
        } catch (IOException e) {
            missing[0] += items.size();
        }
        items.clear();
    }

    // ---------------------------------------------------------------- helpers
    private static Resp conn(Map<String, Resp> pool, String ip, int port) throws IOException {
        String key = ip + ":" + port;
        Resp c = pool.get(key);
        if (c == null) {
            c = new Resp(ip, port);
            pool.put(key, c);
        }
        return c;
    }

    private static Map<String, String> parseColonKv(String s) {
        Map<String, String> m = new HashMap<>();
        for (String line : s.split("\n")) {
            line = line.replace("\r", "");
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            int i = line.indexOf(':');
            if (i > 0)
                m.put(line.substring(0, i), line.substring(i + 1));
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
