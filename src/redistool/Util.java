package redistool;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Process, networking, version and JSON helpers (JDK-only). */
final class Util {
    private Util() {
    }

    /** Result of running an external process. */
    record ExecResult(int code, String out) {
    }

    /** Run a command capturing combined stdout+stderr. Never throws on non-zero. */
    static ExecResult capture(File dir, Map<String, String> extraEnv, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (dir != null)
                pb.directory(dir);
            if (extraEnv != null)
                pb.environment().putAll(extraEnv);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            return new ExecResult(code, new String(out, StandardCharsets.UTF_8).strip());
        } catch (IOException | InterruptedException e) {
            return new ExecResult(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    static ExecResult capture(String... cmd) {
        return capture(null, null, cmd);
    }

    /**
     * Run a command, forwarding stdout/stderr to the user. Returns the exit code.
     */
    static int stream(File dir, Map<String, String> extraEnv, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
            if (dir != null)
                pb.directory(dir);
            if (extraEnv != null)
                pb.environment().putAll(extraEnv);
            return pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    static int stream(File dir, Map<String, String> extraEnv, List<String> cmd) {
        return stream(dir, extraEnv, cmd.toArray(new String[0]));
    }

    /** Block until host:port accepts a TCP connection or the deadline passes. */
    static boolean waitTCP(String host, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 2000);
                return true;
            } catch (IOException e) {
                sleep(1000);
            }
        }
        return false;
    }

    interface Cond {
        boolean ok();
    }

    /** Poll a condition until true or the deadline passes. */
    static boolean pollUntil(long timeoutMs, long intervalMs, Cond fn) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (fn.ok())
                return true;
            if (System.currentTimeMillis() > deadline)
                return false;
            sleep(intervalMs);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final Pattern VER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    static String extractVersion(String s) {
        Matcher m = VER.matcher(s);
        return m.find() ? m.group() : "";
    }

    /** True if ver (X.Y[.Z]) >= min (X.Y). */
    static boolean versionAtLeast(String ver, String min) {
        Matcher v = VER.matcher(ver);
        Matcher mn = VER.matcher(min);
        if (!v.find() || !mn.find())
            return false;
        int vMaj = Integer.parseInt(v.group(1)), vMin = Integer.parseInt(v.group(2));
        int mMaj = Integer.parseInt(mn.group(1)), mMin = Integer.parseInt(mn.group(2));
        return vMaj != mMaj ? vMaj > mMaj : vMin >= mMin;
    }

    /** Minimal JSON string escaping. */
    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20)
                        b.append(String.format("\\u%04x", (int) c));
                    else
                        b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** Serialize a string->string map to a JSON object literal. */
    static String jsonObject(Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first)
                b.append(",");
            first = false;
            b.append('"').append(jsonEscape(e.getKey())).append("\":\"")
                    .append(jsonEscape(e.getValue())).append('"');
        }
        return b.append("}").toString();
    }
}
