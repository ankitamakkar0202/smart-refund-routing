package com.refund.routing.rule;

import com.refund.routing.model.RefundChannel;
import com.refund.routing.model.RefundRequest;
import com.refund.routing.model.RoutingDecision;
import com.refund.routing.registry.ChannelMetadata;
import com.refund.routing.registry.ChannelRegistry;

/**
 * Rule 2 — VIP / Platinum Customer Fast-Path.
 *
 * <p>Triggers when {@code CustomerTier#isPreferred()} is {@code true}
 * (i.e. {@code VIP} or {@code PLATINUM} tier). Prefers
 * {@link RefundChannel#WALLET_CREDIT} for instant settlement. Falls back to
 * {@link RefundChannel#UPI} if the wallet channel is currently unavailable.
 */
public final class VipCustomerRule implements RoutingRule {

    private final ChannelRegistry registry;

    public VipCustomerRule(ChannelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        if (!req.customerTier.isPreferred()) {
            return null;
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

        // Both instant channels are down — pass to next rule
        return null;
    }

    @Override
    public String name() {
        return "VipCustomerRule";
    }
}