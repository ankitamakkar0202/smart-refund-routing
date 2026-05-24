import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full refund routing pipeline.
 *
 * <p>Pipeline stages (in order):
 * <ol>
 *   <li><b>Input validation</b> — zero amount → no-refund decision; negative
 *       amount or blank IDs → validation error.</li>
 *   <li><b>Rate limiting</b> — reuses {@link SlidingWindowRateLimiter} keyed by
 *       {@code merchantId}. Exceeding the limit returns a 429-style error decision.</li>
 *   <li><b>Rule chain</b> — iterates {@link RoutingRule} implementations in
 *       priority order; returns the first non-null decision. Each rule is wrapped
 *       in a try/catch so a buggy rule logs an ERROR and the chain continues.</li>
 * </ol>
 *
 * <p>SOLID alignment:
 * <ul>
 *   <li>SRP — orchestrates; owns no routing logic itself.</li>
 *   <li>OCP — new rules are added to the list; engine code never changes.</li>
 *   <li>DIP — depends on {@link RoutingRule} (abstraction), not concrete rules.</li>
 * </ul>
 */
public final class RefundRoutingEngine {

    private final SlidingWindowRateLimiter rateLimiter;
    private final List<RoutingRule> ruleChain;
    private final ChannelRegistry registry;

    /** Production constructor — builds all dependencies from config. */
    public RefundRoutingEngine(RefundRoutingConfig config) {
        this(config, new ChannelRegistry());
    }

    /** Constructor that accepts an external registry — useful for tests. */
    public RefundRoutingEngine(RefundRoutingConfig config, ChannelRegistry registry) {
        this(config, registry,
                new SlidingWindowRateLimiter(
                        config.getRateLimitRequestsPerWindow(),
                        config.getRateLimitWindowMs()));
    }

    /**
     * Fully injectable constructor — used by tests to control the rate limiter
     * window precisely (mirrors the small-window pattern in RateLimiterTest.java).
     */
    RefundRoutingEngine(RefundRoutingConfig config, ChannelRegistry registry,
                        SlidingWindowRateLimiter rateLimiter) {
        this.registry    = registry;
        this.rateLimiter = rateLimiter;
        this.ruleChain   = buildChain(config, registry);
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Routes the given refund request through the full pipeline.
     *
     * @return always non-null; never throws
     */
    public RoutingDecision route(RefundRequest request) {

        // ── Stage 1: Input validation ──────────────────────────────────────────
        if (request.amount == 0.0) {
            StructuredLogger.event("no_refund_required")
                    .field("requestId",  request.requestId)
                    .field("merchantId", request.merchantId)
                    .field("amount",     request.amount)
                    .info();
            return RoutingDecision.noRefundNeeded(request.requestId, request.transactionDate);
        }

        if (request.amount < 0.0) {
            StructuredLogger.event("invalid_request")
                    .field("requestId", request.requestId)
                    .field("reason",    "negative amount: " + request.amount)
                    .warn();
            return RoutingDecision.error(request.requestId,
                    "Invalid refund amount: " + request.amount + ". Amount must be >= 0.",
                    "VALIDATION_ERROR", request.transactionDate);
        }

        // ── Stage 2: Rate limiting ─────────────────────────────────────────────
        if (!rateLimiter.isAllowed(request.merchantId)) {
            StructuredLogger.event("rate_limit_exceeded")
                    .field("requestId",  request.requestId)
                    .field("merchantId", request.merchantId)
                    .warn();
            return RoutingDecision.error(request.requestId,
                    "Rate limit exceeded for merchant '" + request.merchantId + "'.",
                    "RATE_LIMITED", request.transactionDate);
        }

        // ── Stage 3: Rule chain ────────────────────────────────────────────────
        for (RoutingRule rule : ruleChain) {
            RoutingDecision decision = null;
            try {
                decision = rule.evaluate(request);
            } catch (Exception e) {
                StructuredLogger.event("rule_exception")
                        .field("requestId", request.requestId)
                        .field("ruleName",  rule.name())
                        .field("error",     e.getClass().getSimpleName() + ": " + e.getMessage())
                        .error();
                // Continue to next rule — a buggy rule must not break the chain
                continue;
            }

            if (decision == null) {
                StructuredLogger.event("rule_skipped")
                        .field("requestId", request.requestId)
                        .field("ruleName",  rule.name())
                        .debug();
                continue;
            }

            // Rule matched — log and return
            StructuredLogger.event("refund_routed")
                    .field("requestId",       request.requestId)
                    .field("merchantId",      request.merchantId)
                    .field("customerId",      request.customerId)
                    .field("amount",          request.amount)
                    .field("selectedChannel", decision.selectedChannel.name())
                    .field("appliedRule",     decision.appliedRule)
                    .field("channelScore",    decision.channelScore)
                    .field("processingTimeMs", decision.processingTimeMs)
                    .info();

            return decision;
        }

        // Should never reach here — FallbackRule always returns non-null.
        // Defensive guard.
        return RoutingDecision.error(request.requestId,
                "Internal error: no rule produced a decision.",
                "INTERNAL_ERROR", request.transactionDate);
    }

    public ChannelRegistry getRegistry() {
        return registry;
    }

    /** Shuts down the rate-limiter background cleanup thread. */
    public void close() {
        rateLimiter.close();
    }

    // ─── Chain construction ───────────────────────────────────────────────────

    private static List<RoutingRule> buildChain(RefundRoutingConfig config,
                                                ChannelRegistry registry) {
        List<RoutingRule> chain = new ArrayList<>();
        chain.add(new HighValueRule(config, registry));
        chain.add(new VipCustomerRule(registry));
        chain.add(new OriginalPaymentMethodRule(config, registry));
        chain.add(new ChannelScoringRule(config, registry));
        chain.add(new FallbackRule(registry));
        return chain;
    }
}
