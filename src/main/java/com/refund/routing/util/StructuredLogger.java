package com.refund.routing.util;

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
 *       .field("requestId",        req.requestId)
 *       .field("selectedChannel",  decision.selectedChannel.name())
 *       .field("processingTimeMs", decision.processingTimeMs)
 *       .info();
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
        private final Map<String, String> fields = new LinkedHashMap<>();

        private LogEntry(String eventName) {
            this.eventName = eventName;
        }

        public LogEntry field(String key, String value) {
            fields.put(key, value == null ? null : value);
            return this;
        }

        public LogEntry field(String key, long value) {
            fields.put(key, "#NUM#" + value);
            return this;
        }

        public LogEntry field(String key, double value) {
            fields.put(key, "#NUM#" + String.format("%.4f", value));
            return this;
        }

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

            if (level == Level.ERROR) {
                synchronized (System.err) { System.err.println(sb); }
            } else {
                synchronized (System.out) { System.out.println(sb); }
            }
        }

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
                sb.append(raw.substring(5));
            } else if (raw.startsWith("#BOOL#")) {
                sb.append(raw.substring(6));
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