/**
 * Immutable snapshot of a refund channel's operational metadata.
 *
 * <p>Immutability is intentional: {@link ChannelRegistry} replaces the entire
 * record atomically on updates, so concurrent readers always see a consistent
 * view without locking.
 *
 * <p>Default values registered by {@link ChannelRegistry}:
 * <pre>
 *   WALLET_CREDIT           successRate=0.95  cost= 2.0  settlementHrs= 0.5
 *   UPI                     successRate=0.92  cost= 3.0  settlementHrs= 1.0
 *   ORIGINAL_PAYMENT_METHOD successRate=0.88  cost= 5.0  settlementHrs= 2.0
 *   BANK_TRANSFER           successRate=0.97  cost= 8.0  settlementHrs=24.0
 *   MANUAL_REVIEW           successRate=1.00  cost=15.0  settlementHrs=48.0
 * </pre>
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
        this.channel         = channel;
        this.successRate     = successRate;
        this.costPerTxn      = costPerTxn;
        this.avgSettlementHrs = avgSettlementHrs;
        this.available       = available;
    }

    /**
     * Weighted routing score for this channel.
     *
     * <p>Formula: {@code (successRate × 0.6) − (normalizedCost × 0.4)}
     *
     * <p>The caller ({@link ChannelScoringRule}) is responsible for normalising
     * cost across all channels ({@code costPerTxn / maxCostAcrossChannels}) before
     * passing it here, because normalisation is a relative operation that requires
     * knowledge of all channels' costs.
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
