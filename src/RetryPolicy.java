import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable retry policy attached to every successful {@link RoutingDecision}.
 *
 * <p>The routing engine produces this once; the calling payment platform uses it
 * to drive retries without needing to call the routing service again. This keeps
 * retry orchestration concerns in the caller while the routing logic stays here.
 *
 * <p>Backoff schedule example with {@code initialDelayMs=1000}, {@code multiplier=2.0}:
 * <pre>
 *   Attempt 1 → primary channel (no delay)
 *   Attempt 2 → fallback[0], wait 1 000 ms
 *   Attempt 3 → fallback[1], wait 2 000 ms
 * </pre>
 *
 * <p>Configuration values come from {@link RefundRoutingConfig}.
 */
public final class RetryPolicy {

    /** Maximum total attempts (primary + retries). Sourced from config. */
    public final int maxAttempts;

    /** Delay before the first retry in milliseconds. Sourced from config. */
    public final long initialDelayMs;

    /** Multiplier applied to delay on each subsequent retry (exponential backoff). */
    public final double backoffMultiplier;

    /**
     * Channels to try in order if the primary channel fails.
     * Length is at most {@code maxAttempts - 1}.
     * Ranked by weighted score (best first) by {@link ChannelScoringRule}.
     */
    public final List<RefundChannel> fallbackOrder;

    public RetryPolicy(int maxAttempts, long initialDelayMs,
                       double backoffMultiplier, List<RefundChannel> fallbackOrder) {
        this.maxAttempts      = maxAttempts;
        this.initialDelayMs   = initialDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        // Defensive copy + immutable view
        this.fallbackOrder    = Collections.unmodifiableList(
                new ArrayList<>(fallbackOrder));
    }

    /**
     * Serialises this policy to a JSON object string.
     * Hand-built — no external libraries required.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"maxAttempts\":").append(maxAttempts).append(",");
        sb.append("\"initialDelayMs\":").append(initialDelayMs).append(",");
        sb.append("\"backoffMultiplier\":").append(String.format("%.1f", backoffMultiplier)).append(",");
        sb.append("\"fallbackOrder\":[");
        for (int i = 0; i < fallbackOrder.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(fallbackOrder.get(i).name()).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxAttempts=" + maxAttempts
                + ", initialDelayMs=" + initialDelayMs
                + ", backoffMultiplier=" + backoffMultiplier
                + ", fallbackOrder=" + fallbackOrder + "}";
    }
}
