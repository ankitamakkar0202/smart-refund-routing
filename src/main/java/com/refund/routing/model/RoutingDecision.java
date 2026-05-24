package com.refund.routing.model;

/**
 * Immutable DTO representing the outcome of a refund routing decision.
 *
 * <p>Three factory paths:
 * <ul>
 *   <li>Normal constructor — successful routing with a {@link RetryPolicy}</li>
 *   <li>{@link #error(String, String, String, long)} — rate-limit exceeded or validation failure</li>
 *   <li>{@link #noRefundNeeded(String, long)} — zero-amount transaction; no channel needed</li>
 * </ul>
 *
 * <p>JSON serialisation is hand-built via {@link #toJson()} — no external library.
 */
public final class RoutingDecision {

    public final String requestId;
    public final RefundChannel selectedChannel;
    public final String reason;
    /** Name of the routing rule that produced this decision. */
    public final String appliedRule;
    /** Weighted score of the selected channel; 0.0 for non-scored paths. */
    public final double channelScore;
    /** Echo of {@link RefundRequest#transactionDate} for downstream logging. */
    public final long transactionDate;
    /** Epoch ms when this decision was made. */
    public final long decidedAtEpochMs;
    /** Elapsed ms between original transaction and this routing decision. */
    public final long processingTimeMs;
    /**
     * Retry policy for the caller — {@code null} for error and no-refund decisions.
     */
    public final RetryPolicy retryPolicy;

    /** Standard routing decision constructor. */
    public RoutingDecision(String requestId, RefundChannel selectedChannel,
                           String reason, String appliedRule,
                           double channelScore, long transactionDate,
                           RetryPolicy retryPolicy) {
        this.requestId        = requestId;
        this.selectedChannel  = selectedChannel;
        this.reason           = reason;
        this.appliedRule      = appliedRule;
        this.channelScore     = channelScore;
        this.transactionDate  = transactionDate;
        this.decidedAtEpochMs = System.currentTimeMillis();
        this.processingTimeMs = this.decidedAtEpochMs - transactionDate;
        this.retryPolicy      = retryPolicy;
    }

    // Private constructor for error and no-refund cases (no RetryPolicy).
    private RoutingDecision(String requestId, RefundChannel selectedChannel,
                            String reason, String appliedRule, long transactionDate) {
        this.requestId        = requestId;
        this.selectedChannel  = selectedChannel;
        this.reason           = reason;
        this.appliedRule      = appliedRule;
        this.channelScore     = 0.0;
        this.transactionDate  = transactionDate;
        this.decidedAtEpochMs = System.currentTimeMillis();
        this.processingTimeMs = this.decidedAtEpochMs - transactionDate;
        this.retryPolicy      = null;
    }

    // ─── Factory methods ──────────────────────────────────────────────────────

    /**
     * Creates an error decision (rate-limit exceeded, validation failure, etc.).
     *
     * @param appliedRule  use {@code "RATE_LIMITED"} or {@code "VALIDATION_ERROR"}
     */
    public static RoutingDecision error(String requestId, String reason,
                                        String appliedRule, long transactionDate) {
        return new RoutingDecision(requestId, RefundChannel.ERROR,
                reason, appliedRule, transactionDate);
    }

    /**
     * Creates a no-refund decision for zero-amount transactions.
     * HTTP status will be 200 — this is a valid business outcome, not an error.
     */
    public static RoutingDecision noRefundNeeded(String requestId, long transactionDate) {
        return new RoutingDecision(requestId, RefundChannel.NONE,
                "No refund required — transaction amount is zero",
                "VALIDATION", transactionDate);
    }

    // ─── JSON serialisation ───────────────────────────────────────────────────

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendStr(sb, "requestId",       requestId,              true);
        appendStr(sb, "selectedChannel", selectedChannel.name(), false);
        appendStr(sb, "reason",          reason,                 false);
        appendStr(sb, "appliedRule",     appliedRule,            false);
        sb.append(",\"channelScore\":").append(String.format("%.4f", channelScore));
        sb.append(",\"transactionDate\":").append(transactionDate);
        sb.append(",\"decidedAtEpochMs\":").append(decidedAtEpochMs);
        sb.append(",\"processingTimeMs\":").append(processingTimeMs);
        sb.append(",\"retryPolicy\":");
        if (retryPolicy == null) {
            sb.append("null");
        } else {
            sb.append(retryPolicy.toJson());
        }
        sb.append("}");
        return sb.toString();
    }

    private static void appendStr(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(",");
        sb.append("\"").append(key).append("\":\"").append(escape(value)).append("\"");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return "RoutingDecision{requestId='" + requestId
                + "', selectedChannel=" + selectedChannel
                + ", appliedRule='" + appliedRule + "'"
                + ", processingTimeMs=" + processingTimeMs + "}";
    }
}
