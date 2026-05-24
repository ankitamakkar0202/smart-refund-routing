package com.refund.routing.registry;

import com.refund.routing.model.RefundChannel;

/**
 * Immutable snapshot of a refund channel's operational metadata.
 *
 * <p>Immutability is intentional: {@link ChannelRegistry} replaces the entire
 * record atomically on updates, so concurrent readers always see a consistent
 * view without locking.
 */
public final class ChannelMetadata {

    /** The channel this record describes. */
    public final RefundChannel channel;

    /** Fraction of refunds processed successfully through this channel (0.0–1.0). */
    public final double successRate;

    /** Fixed cost per refund transaction in currency units (e.g. INR). */
    public final double costPerTxn;

    /** Average time from refund initiation to customer credit (hours). */
    public final double avgSettlementHrs;

    /** Whether this channel is currently accepting refunds. */
    public final boolean available;

    public ChannelMetadata(RefundChannel channel, double successRate,
                           double costPerTxn, double avgSettlementHrs,
                           boolean available) {
        this.channel          = channel;
        this.successRate      = successRate;
        this.costPerTxn       = costPerTxn;
        this.avgSettlementHrs = avgSettlementHrs;
        this.available        = available;
    }

    /**
     * Weighted routing score for this channel.
     *
     * <p>Formula: {@code (successRate × 0.6) − (normalizedCost × 0.4)}
     *
     * @param normalizedCost pre-normalised cost value in [0.0, 1.0]
     * @return routing score; higher is better
     */
    public double score(double normalizedCost) {
        return (successRate * 0.6) - (normalizedCost * 0.4);
    }

    /** Returns a copy of this record with {@code available} set to the given value. */
    public ChannelMetadata withAvailability(boolean newAvailability) {
        return new ChannelMetadata(channel, successRate, costPerTxn,
                avgSettlementHrs, newAvailability);
    }

    @Override
    public String toString() {
        return "ChannelMetadata{channel=" + channel
                + ", successRate=" + successRate
                + ", costPerTxn=" + costPerTxn
                + ", avgSettlementHrs=" + avgSettlementHrs
                + ", available=" + available + "}";
    }
}
