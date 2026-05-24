import java.util.ArrayList;
import java.util.List;

/**
 * Rule 4 — Weighted Channel Scorer.
 *
 * <p>Scores every available channel using the formula:
 * <pre>
 *   score = (successRate × 0.6) − (normalizedCost × 0.4)
 *   normalizedCost = channel.costPerTxn / maxCostAcrossAvailableChannels
 * </pre>
 *
 * <p>The highest-scoring channel becomes the {@code selectedChannel}.
 * All remaining channels (in score order) populate {@link RetryPolicy#fallbackOrder},
 * giving the caller a ranked fallback sequence if the primary channel fails.
 *
 * <p><b>Small-amount shortcut:</b> when {@code amount < routing.small_amount_threshold}
 * (default 500), WALLET_CREDIT is chosen immediately if available — bypassing full
 * scoring for micro-refunds where instant settlement always wins. This shortcut runs
 * <em>after</em> Rules 1–3, so a small-amount request with a valid original method
 * (Rule 3) still routes correctly.
 *
 * <p>Returns {@code null} only when the channel registry contains no available
 * channels at all, allowing {@link FallbackRule} to take over.
 */
public final class ChannelScoringRule implements RoutingRule {

    private final double smallAmountThreshold;
    private final ChannelRegistry registry;
    private final RefundRoutingConfig config;

    /**
     * @param config   provides thresholds and retry policy settings
     * @param registry live channel metadata store
     */
    public ChannelScoringRule(RefundRoutingConfig config, ChannelRegistry registry) {
        this.config               = config;
        this.registry             = registry;
        this.smallAmountThreshold = config.getSmallAmountThreshold();
    }

    @Override
    public RoutingDecision evaluate(RefundRequest req) {
        List<ChannelMetadata> available = registry.getAvailableChannels();
        if (available.isEmpty()) {
            return null; // nothing to score — FallbackRule takes over
        }

        // ── Small-amount shortcut ──────────────────────────────────────────────
        if (req.amount < smallAmountThreshold) {
            ChannelMetadata wallet = registry.getMetadata(RefundChannel.WALLET_CREDIT);
            if (wallet != null && wallet.available) {
                RetryPolicy retryPolicy = buildRetryPolicy(RefundChannel.WALLET_CREDIT, available);
                return new RoutingDecision(req.requestId, RefundChannel.WALLET_CREDIT,
                        String.format("Small refund (amount=%.2f < threshold=%.0f) — instant wallet credit.",
                                req.amount, smallAmountThreshold),
                        name(), wallet.score(0.0), req.transactionDate, retryPolicy);
            }
        }

        // ── Full weighted scoring ──────────────────────────────────────────────

        // 1. Find max cost for normalisation (guard against all-zero costs)
        double maxCost = 0.0;
        for (ChannelMetadata m : available) {
            if (m.costPerTxn > maxCost) maxCost = m.costPerTxn;
        }
        if (maxCost == 0.0) maxCost = 1.0; // all channels are free — normalise to 0

        // 2. Score every available channel
        List<ScoredChannel> scored = new ArrayList<>();
        for (ChannelMetadata m : available) {
            double normalizedCost = m.costPerTxn / maxCost;
            scored.add(new ScoredChannel(m, m.score(normalizedCost)));
        }

        // 3. Sort descending by score (simple insertion sort — list is tiny ≤ 5)
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        ScoredChannel winner = scored.get(0);
        RetryPolicy retryPolicy = buildRetryPolicy(winner.metadata.channel, available);

        return new RoutingDecision(req.requestId, winner.metadata.channel,
                String.format("Best scored channel (score=%.4f).", winner.score),
                name(), winner.score, req.transactionDate, retryPolicy);
    }

    /**
     * Builds a {@link RetryPolicy} whose {@code fallbackOrder} contains all
     * available channels except the selected one, ranked by score.
     */
    private RetryPolicy buildRetryPolicy(RefundChannel selected,
                                         List<ChannelMetadata> available) {
        // Re-score to build a consistent ranked list (may differ from shortcut path)
        double maxCost = 0.0;
        for (ChannelMetadata m : available) {
            if (m.costPerTxn > maxCost) maxCost = m.costPerTxn;
        }
        if (maxCost == 0.0) maxCost = 1.0;

        List<ScoredChannel> ranked = new ArrayList<>();
        for (ChannelMetadata m : available) {
            if (m.channel != selected) {
                double normalizedCost = m.costPerTxn / maxCost;
                ranked.add(new ScoredChannel(m, m.score(normalizedCost)));
            }
        }
        ranked.sort((a, b) -> Double.compare(b.score, a.score));

        List<RefundChannel> fallbackOrder = new ArrayList<>();
        // Cap fallback list at (maxAttempts - 1) so we never exceed the configured limit
        int maxFallbacks = Math.max(0, config.getRetryMaxAttempts() - 1);
        for (int i = 0; i < Math.min(ranked.size(), maxFallbacks); i++) {
            fallbackOrder.add(ranked.get(i).metadata.channel);
        }

        return new RetryPolicy(
                config.getRetryMaxAttempts(),
                config.getRetryInitialDelayMs(),
                config.getRetryBackoffMultiplier(),
                fallbackOrder);
    }

    @Override
    public String name() {
        return "ChannelScoringRule";
    }

    // ── Inner helper ─────────────────────────────────────────────────────────

    private static final class ScoredChannel {
        final ChannelMetadata metadata;
        final double score;

        ScoredChannel(ChannelMetadata metadata, double score) {
            this.metadata = metadata;
            this.score    = score;
        }
    }
}
