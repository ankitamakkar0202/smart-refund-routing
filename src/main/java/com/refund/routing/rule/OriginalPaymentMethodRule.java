package com.refund.routing.rule;

import com.refund.routing.config.RefundRoutingConfig;
import com.refund.routing.model.RefundChannel;
import com.refund.routing.model.RefundRequest;
import com.refund.routing.model.RoutingDecision;
import com.refund.routing.registry.ChannelMetadata;
import com.refund.routing.registry.ChannelRegistry;

/**
 * Rule 3 — Prefer Original Payment Method.
 *
 * <p>Triggers when two conditions are both true:
 * <ol>
 *   <li>{@link RefundRequest#originalMethodAvailable} is {@code true}</li>
 *   <li>The {@link RefundChannel#ORIGINAL_PAYMENT_METHOD} channel's success rate
 *       exceeds {@code routing.original_method_min_success_rate} (default 0.85).</li>
 * </ol>
 */
public final class OriginalPaymentMethodRule implements RoutingRule {

    private final double minSuccessRate;
    private final ChannelRegistry registry;

    public OriginalPaymentMethodRule(RefundRoutingConfig config, ChannelRegistry registry) {
        this.minSuccessRate = config.getOriginalMethodMinSuccessRate();
        this.registry       = registry;
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        if (!req.originalMethodAvailable) {
            return null;
        }

        ChannelMetadata meta = registry.getMetadata(RefundChannel.ORIGINAL_PAYMENT_METHOD);
        if (meta == null || !meta.available) {
            return null;
        }

        if (meta.successRate <= minSuccessRate) {
            return null;
        }

        String reason = String.format(
                "Original payment method available (successRate=%.1f%% > threshold=%.0f%%).",
                meta.successRate * 100, minSuccessRate * 100);

        return new RoutingDecision(req.requestId, RefundChannel.ORIGINAL_PAYMENT_METHOD,
                reason, name(), meta.successRate, req.transactionDate, null);
    }

    @Override
    public String name() {
        return "OriginalPaymentMethodRule";
    }
}