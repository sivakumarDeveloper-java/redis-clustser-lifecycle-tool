package redistool;

import java.util.ArrayList;
import java.util.List;

/**
 * Cluster topology model + parsing of the node-agent's tab-delimited output.
 */
final class Cluster {
    private Cluster() {
    }

    /** One cluster node's state (built by the agent's `facts` command). */
    static final class NodeInfo {
        String id, ip;
        int port;
        String role = "?", masterId = "", link = "", version = "?", mem = "?";
        int keys;
        String masterLinkStatus = "", replicating = "";
        List<String> slots = new ArrayList<>();
    }

    static final class Facts {
        String clusterState = "unknown";
        List<NodeInfo> nodes = new ArrayList<>();

        List<NodeInfo> masters() {
            List<NodeInfo> m = new ArrayList<>();
            for (NodeInfo n : nodes)
                if ("master".equals(n.role))
                    m.add(n);
            m.sort((a, b) -> a.ip.compareTo(b.ip));
            return m;
        }

        List<NodeInfo> replicas() {
            List<NodeInfo> r = new ArrayList<>();
            for (NodeInfo n : nodes)
                if ("replica".equals(n.role))
                    r.add(n);
            r.sort((a, b) -> a.ip.compareTo(b.ip));
            return r;
        }

        int slotsCovered() {
            int total = 0;
            for (NodeInfo n : masters()) {
                for (String tok : n.slots) {
                    if (tok.startsWith("["))
                        continue; // migration markers
                    int dash = tok.indexOf('-');
                    try {
                        if (dash > 0) {
                            total += Integer.parseInt(tok.substring(dash + 1))
                                    - Integer.parseInt(tok.substring(0, dash)) + 1;
                        } else {
                            Integer.parseInt(tok);
                            total += 1;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return total;
        }

        NodeInfo byIp(String ip) {
            for (NodeInfo n : nodes)
                if (n.ip.equals(ip))
                    return n;
            return null;
        }
    }

    static final class SeedResult {
        int requested, inserted, failures;
        List<String[]> distribution = new ArrayList<>(); // [ip, count]
    }

    static final class VerifyResult {
        int total, verified, missing, mismatched;
        boolean pass;
    }

    /** Parse the agent's `facts` output. Throws on an ERROR line. */
    static Facts parseFacts(String text) {
        Facts f = new Facts();
        for (String line : text.split("\n")) {
            if (line.isBlank())
                continue;
            String[] t = line.split("\t", -1);
            switch (t[0]) {
                case "ERROR" -> throw new RuntimeException("agent error: " + (t.length > 1 ? t[1] : "unknown"));
                case "INFO" -> {
                    if (t.length > 1)
                        f.clusterState = t[1];
                }
                case "NODE" -> {
                    // NODE id ip port role master_id link version mem keys mlink replicating slots
                    NodeInfo n = new NodeInfo();
                    n.id = t[1];
                    n.ip = t[2];
                    n.port = parseInt(t[3], 6379);
                    n.role = t[4];
                    n.masterId = t[5];
                    n.link = t[6];
                    n.version = t[7];
                    n.mem = t[8];
                    n.keys = parseInt(t[9], 0);
                    n.masterLinkStatus = t.length > 10 ? t[10] : "";
                    n.replicating = t.length > 11 ? t[11] : "";
                    if (t.length > 12 && !t[12].isEmpty()) {
                        for (String s : t[12].split(","))
                            if (!s.isEmpty())
                                n.slots.add(s);
                    }
                    f.nodes.add(n);
                }
                default -> {
                }
            }
        }
        return f;
    }

    static SeedResult parseSeed(String text) {
        SeedResult r = new SeedResult();
        for (String line : text.split("\n")) {
            if (line.isBlank())
                continue;
            String[] t = line.split("\t", -1);
            switch (t[0]) {
                case "ERROR" -> throw new RuntimeException("agent error: " + (t.length > 1 ? t[1] : "unknown"));
                case "SEED" -> {
                    r.requested = parseInt(t[1], 0);
                    r.inserted = parseInt(t[2], 0);
                    r.failures = parseInt(t[3], 0);
                }
                case "DIST" -> r.distribution.add(new String[] { t[1], t[2] });
                default -> {
                }
            }
        }
        return r;
    }

    static VerifyResult parseVerify(String text) {
        VerifyResult r = new VerifyResult();
        for (String line : text.split("\n")) {
            if (line.isBlank())
                continue;
            String[] t = line.split("\t", -1);
            switch (t[0]) {
                case "ERROR" -> {
                    /* leave pass=false, zeros */ }
                case "VERIFY" -> {
                    r.total = parseInt(t[1], 0);
                    r.verified = parseInt(t[2], 0);
                    r.missing = parseInt(t[3], 0);
                    r.mismatched = parseInt(t[4], 0);
                    r.pass = "true".equals(t[5]);
                }
                default -> {
                }
            }
        }
        return r;
    }

    static void printStatus(Facts f) {
        System.out.printf("%nCluster State: %s%n%n", f.clusterState);
        System.out.println("MASTERS");
        for (NodeInfo n : f.masters()) {
            System.out.printf("  %-15s [master]  v%-7s slots: %-18s keys: %-5d mem: %s%n",
                    n.ip + ":" + n.port, n.version, slotSummary(n), n.keys, n.mem);
        }
        System.out.println("\nREPLICAS");
        for (NodeInfo n : f.replicas()) {
            String rep = n.replicating.isEmpty() ? "?" : n.replicating + ":6379";
            System.out.printf("  %-15s [replica] v%-7s replicating: %-18s mem: %s%n",
                    n.ip + ":" + n.port, n.version, rep, n.mem);
        }
        System.out.println();
    }

    private static String slotSummary(NodeInfo n) {
        return n.slots.isEmpty() ? "-" : String.join(",", n.slots);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
