package com.refund.routing.rule;

import com.refund.routing.model.RefundChannel;
import com.refund.routing.model.RefundRequest;
import com.refund.routing.model.RoutingDecision;
import com.refund.routing.registry.ChannelMetadata;
import com.refund.routing.registry.ChannelRegistry;

/**
 * Rule 5 — Fallback (Safety Net).
 *
 * <p>The final rule in the chain. Guaranteed to return a non-null decision so
 * the engine never produces a {@code null} routing result.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Prefer {@link RefundChannel#BANK_TRANSFER} — highest success rate.</li>
 *   <li>If BANK_TRANSFER is also unavailable, escalate to
 *       {@link RefundChannel#MANUAL_REVIEW} — 100% processing guarantee.</li>
 * </ol>
 */
public final class FallbackRule implements RoutingRule {

    private final ChannelRegistry registry;

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

        return new RoutingDecision(req.requestId, selected, reason,
                name(), 0.0, req.transactionDate, null);
    }

    @Override
    public String name() {
        return "FallbackRule";
    }
}