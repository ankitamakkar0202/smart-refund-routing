package com.refund.routing.rule;

import com.refund.routing.config.RefundRoutingConfig;
import com.refund.routing.model.RefundChannel;
import com.refund.routing.model.RefundRequest;
import com.refund.routing.model.RoutingDecision;
import com.refund.routing.registry.ChannelMetadata;
import com.refund.routing.registry.ChannelRegistry;

/**
 * Rule 1 — High-Value Refund.
 *
 * <p>Triggers when {@code amount > routing.high_value_threshold} (default 50 000).
 * Routes to {@link RefundChannel#BANK_TRANSFER} for reliable settlement of large
 * amounts. Falls back to {@link RefundChannel#MANUAL_REVIEW} if the bank-transfer
 * channel is currently marked unavailable in the {@link ChannelRegistry}.
 *
 * <p>This rule fires before {@code VipCustomerRule}, so a VIP customer requesting
 * a high-value refund is still routed via the bank channel — safety takes priority
 * over speed for large transactions.
 */
public final class HighValueRule implements RoutingRule {

    private final double highValueThreshold;
    private final ChannelRegistry registry;

    public HighValueRule(RefundRoutingConfig config, ChannelRegistry registry) {
        this.highValueThreshold = config.getHighValueThreshold();
        this.registry           = registry;
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        if (req.amount <= highValueThreshold) {
            return null;
        }

        ChannelMetadata bankTransfer = registry.getMetadata(RefundChannel.BANK_TRANSFER);
        boolean bankAvailable = bankTransfer != null && bankTransfer.available;

        RefundChannel selected = bankAvailable
                ? RefundChannel.BANK_TRANSFER
                : RefundChannel.MANUAL_REVIEW;

        String reason = String.format(
                "High-value refund (amount=%.2f > threshold=%.0f). Routed to %s.",
                req.amount, highValueThreshold, selected.name());

        if (!bankAvailable) {
            reason += " BANK_TRANSFER is currently unavailable.";
        }

        return new RoutingDecision(req.requestId, selected, reason,
                name(), 0.0, req.transactionDate, null);
    }

    @Override
    public String name() {
        return "HighValueRule";
    }
}