import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight structured logger that emits one JSON object per log line (JSONL).
 *
 * <p>SRP: single job — format and write a JSON log line to stdout/stderr.
 * Thread-safe: {@link LogEntry#log} synchronises on {@code System.out}.
 *
 * <p>Usage:
 * <pre>
 *   StructuredLogger.event("refund_routed")
 *       .field("requestId",       req.requestId)
 *       .field("selectedChannel", decision.selectedChannel.name())
 *       .field("processingTimeMs", decision.processingTimeMs)
 *       .info();
 * </pre>
 *
 * Output:
 * <pre>
 *   {"timestamp":"2026-05-24T10:30:00.123Z","level":"INFO","event":"refund_routed",
 *    "requestId":"req-123","selectedChannel":"WALLET_CREDIT","processingTimeMs":7200000}
 * </pre>
 */
public final class StructuredLogger {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                             .withZone(ZoneOffset.UTC);

    public enum Level { DEBUG, INFO, WARN, ERROR }

    /** Begin building a log entry for the named event. */
    public static LogEntry event(String eventName) {
        return new LogEntry(eventName);
    }

    // ─── LogEntry ─────────────────────────────────────────────────────────────

    public static final class LogEntry {

        private final String eventName;
        // LinkedHashMap preserves insertion order so JSON fields are predictable.
        private final Map<String, String> fields = new LinkedHashMap<>();

        private LogEntry(String eventName) {
            this.eventName = eventName;
        }

        /** Add a String field. Null values are serialised as JSON {@code null}. */
        public LogEntry field(String key, String value) {
            fields.put(key, value == null ? null : value);
            return this;
        }

        /** Add a numeric field (long). */
        public LogEntry field(String key, long value) {
            fields.put(key, "#NUM#" + value);
            return this;
        }

        /** Add a numeric field (double), formatted to 4 decimal places. */
        public LogEntry field(String key, double value) {
            fields.put(key, "#NUM#" + String.format("%.4f", value));
            return this;
        }

        /** Add a boolean field. */
        public LogEntry field(String key, boolean value) {
            fields.put(key, "#BOOL#" + value);
            return this;
        }

        public void debug() { log(Level.DEBUG); }
        public void info()  { log(Level.INFO);  }
        public void warn()  { log(Level.WARN);  }
        public void error() { log(Level.ERROR); }

        public void log(Level level) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            appendStringField(sb, "timestamp", ISO.format(Instant.now()), true);
            appendStringField(sb, "level",     level.name(), false);
            appendStringField(sb, "event",     eventName,    false);

            for (Map.Entry<String, String> e : fields.entrySet()) {
                sb.append(",");
                appendField(sb, e.getKey(), e.getValue());
            }

            sb.append("}");

            // Errors go to stderr; everything else to stdout. Synchronise to
            // avoid interleaved output from multiple HTTP worker threads.
            if (level == Level.ERROR) {
                synchronized (System.err) { System.err.println(sb); }
            } else {
                synchronized (System.out) { System.out.println(sb); }
            }
        }

        // ── JSON serialisation helpers ─────────────────────────────────────

        private static void appendStringField(StringBuilder sb, String key,
                                              String value, boolean first) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(key)).append("\":\"").append(escape(value)).append("\"");
        }

        private static void appendField(StringBuilder sb, String key, String raw) {
            sb.append("\"").append(escape(key)).append("\":");
            if (raw == null) {
                sb.append("null");
            } else if (raw.startsWith("#NUM#")) {
                sb.append(raw.substring(5));          // emit as bare number
            } else if (raw.startsWith("#BOOL#")) {
                sb.append(raw.substring(6));          // emit as bare boolean
            } else {
                sb.append("\"").append(escape(raw)).append("\"");
            }
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}
