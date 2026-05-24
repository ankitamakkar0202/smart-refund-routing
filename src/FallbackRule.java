/**
 * Rule 5 — Fallback (Safety Net).
 *
 * <p>The final rule in the chain. Guaranteed to return a non-null decision so
 * the engine never produces a {@code null} routing result.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Prefer {@link RefundChannel#BANK_TRANSFER} — highest success rate among
 *       standard channels (0.97).</li>
 *   <li>If BANK_TRANSFER is also unavailable in the registry, fall back to
 *       {@link RefundChannel#MANUAL_REVIEW} — the absolute last resort, which
 *       has a 100% processing guarantee (human review).</li>
 * </ol>
 *
 * <p>This rule is only reached when all prior rules returned {@code null}, which
 * means no channels scored (all unavailable) and no special-case rules fired.
 * In practice this should be rare; its presence ensures the system never silently
 * drops a refund request.
 */
public final class FallbackRule implements RoutingRule {

    private final ChannelRegistry registry;

    /**
     * @param registry queried to check whether BANK_TRANSFER is currently available
     */
    public FallbackRule(ChannelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        ChannelMetadata bankTransfer = registry.getMetadata(RefundChannel.BANK_TRANSFER);
        boolean bankAvailable = bankTransfer != null && bankTransfer.available;

        RefundChannel selected = bankAvailable
                ? RefundChannel.BANK_TRANSFER
                : RefundChannel.MANUAL_REVIEW;

        String reason = "No specific rule matched — defaulting to " + selected.name() + ".";
        if (!bankAvailable) {
            reason += " BANK_TRANSFER is unavailable; escalating to MANUAL_REVIEW.";
        }

        // Fallback decisions don't carry a retry policy — by definition we've
        // exhausted all scored options. The caller should escalate to support.
        return new RoutingDecision(req.requestId, selected, reason,
                name(), 0.0, req.transactionDate, null);
    }

    @Override
    public String name() {
        return "FallbackRule";
    }
}
