package redistool;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured JSONL operation log (stretch S5) + colored console output. */
final class Logx {
    private static final String CYAN = "\u001b[36m", GREEN = "\u001b[32m",
            YELLOW = "\u001b[33m", RESET = "\u001b[0m";

    private Writer w;

    Logx(Path logsDir, String cmd) {
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path p = logsDir.resolve(cmd + "-" + ts + ".jsonl");
            w = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
            event("start", Map.of("command", cmd));
        } catch (IOException e) {
            w = null;
        }
    }

    void event(String kind, Map<String, String> fields) {
        if (w == null)
            return;
        Map<String, String> rec = new LinkedHashMap<>();
        rec.put("ts", Instant.now().toString());
        rec.put("event", kind);
        if (fields != null)
            rec.putAll(fields);
        try {
            w.write(Util.jsonObject(rec));
            w.write("\n");
            w.flush();
        } catch (IOException ignored) {
        }
    }

    void close() {
        if (w == null)
            return;
        event("end", null);
        try {
            w.close();
        } catch (IOException ignored) {
        }
    }

    void info(String fmt, Object... a) {
        String m = String.format(fmt, a);
        System.out.println(m);
        event("info", Map.of("msg", m));
    }

    void step(String fmt, Object... a) {
        String m = String.format(fmt, a);
        System.out.println(CYAN + "→ " + m + RESET);
        event("step", Map.of("msg", m));
    }

    void ok(String fmt, Object... a) {
        String m = String.format(fmt, a);
        System.out.println(GREEN + "✓ " + m + RESET);
        event("ok", Map.of("msg", m));
    }

    void warn(String fmt, Object... a) {
        String m = String.format(fmt, a);
        System.out.println(YELLOW + "! " + m + RESET);
        event("warn", Map.of("msg", m));
    }
}
