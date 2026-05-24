/**
 * Rule 3 — Prefer Original Payment Method.
 *
 * <p>Triggers when two conditions are both true:
 * <ol>
 *   <li>{@link RefundRequest#originalMethodAvailable} is {@code true} — the
 *       caller signals that the original instrument (card, UPI, etc.) is still
 *       active and reachable.</li>
 *   <li>The {@link RefundChannel#ORIGINAL_PAYMENT_METHOD} channel's success rate
 *       in the {@link ChannelRegistry} exceeds
 *       {@code routing.original_method_min_success_rate} (default 0.85).</li>
 * </ol>
 *
 * <p>Routing back to the original method is generally the customer's preferred
 * experience. The success-rate guard prevents routing to a degraded channel.
 */
public final class OriginalPaymentMethodRule implements RoutingRule {

    private final double minSuccessRate;
    private final ChannelRegistry registry;

    /**
     * @param config   provides {@code routing.original_method_min_success_rate}
     * @param registry queried for live ORIGINAL_PAYMENT_METHOD metadata
     */
    public OriginalPaymentMethodRule(RefundRoutingConfig config, ChannelRegistry registry) {
        this.minSuccessRate = config.getOriginalMethodMinSuccessRate();
        this.registry       = registry;
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        if (!req.originalMethodAvailable) {
            return null; // original instrument not available — pass to next rule
        }

        ChannelMetadata meta = registry.getMetadata(RefundChannel.ORIGINAL_PAYMENT_METHOD);
        if (meta == null || !meta.available) {
            return null; // channel is down in the registry
        }

        if (meta.successRate <= minSuccessRate) {
            return null; // success rate below threshold — not reliable enough
        }

        String reason = String.format(
                "Original payment method available (successRate=%.1f%% > threshold=%.0f%%).",
                meta.successRate * 100, minSuccessRate * 100);

        // No retry policy here — if the original method fails, the caller should
        // re-route via POST /api/v1/refund/route with originalMethodAvailable=false.
        return new RoutingDecision(req.requestId, RefundChannel.ORIGINAL_PAYMENT_METHOD,
                reason, name(), meta.successRate, req.transactionDate, null);
    }

    @Override
    public String name() {
        return "OriginalPaymentMethodRule";
    }
}
