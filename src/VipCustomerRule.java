/**
 * Rule 2 — VIP / Platinum Customer Fast-Path.
 *
 * <p>Triggers when {@link CustomerTier#isPreferred()} is {@code true}
 * (i.e. {@code VIP} or {@code PLATINUM} tier). Prefers
 * {@link RefundChannel#WALLET_CREDIT} for instant settlement. Falls back to
 * {@link RefundChannel#UPI} if the wallet channel is currently unavailable.
 *
 * <p>Does <em>not</em> carry a {@link RetryPolicy} — premium customers get a
 * deterministic fast-path. Retry complexity would slow down an already-instant path.
 */
public final class VipCustomerRule implements RoutingRule {

    private final ChannelRegistry registry;

    /**
     * @param registry queried to check live availability of WALLET_CREDIT and UPI
     */
    public VipCustomerRule(ChannelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        if (!req.customerTier.isPreferred()) {
            return null; // not applicable — pass to next rule
        }

        ChannelMetadata wallet = registry.getMetadata(RefundChannel.WALLET_CREDIT);
        if (wallet != null && wallet.available) {
            return new RoutingDecision(req.requestId, RefundChannel.WALLET_CREDIT,
                    req.customerTier.name() + " customer — instant wallet credit",
                    name(), 0.0, req.transactionDate, null);
        }

        // Wallet is down — fall back to UPI (next fastest)
        ChannelMetadata upi = registry.getMetadata(RefundChannel.UPI);
        String reason = req.customerTier.name()
                + " customer — UPI fallback (WALLET_CREDIT currently unavailable)";

        if (upi != null && upi.available) {
            return new RoutingDecision(req.requestId, RefundChannel.UPI,
                    reason, name(), 0.0, req.transactionDate, null);
        }

        // Both instant channels are down — pass to next rule for full scoring
        return null;
    }

    @Override
    public String name() {
        return "VipCustomerRule";
    }
}
