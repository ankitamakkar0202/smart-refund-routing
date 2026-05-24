package com.refund.routing.rule;

import com.refund.routing.model.RefundRequest;
import com.refund.routing.model.RoutingDecision;

/**
 * Contract for a single routing rule in the chain-of-responsibility.
 *
 * <p>SOLID alignment:
 * <ul>
 *   <li><b>OCP</b> — new routing criteria are added by implementing this interface,
 *       never by modifying {@code RefundRoutingEngine}.</li>
 *   <li><b>LSP</b> — every implementation must return a non-null {@link RoutingDecision}
 *       if the rule applies, or {@code null} to pass control to the next rule.</li>
 *   <li><b>ISP</b> — the interface is intentionally narrow (two methods).</li>
 * </ul>
 *
 * <p>Thread safety: implementations must be safe for concurrent calls from the
 * HTTP worker thread pool.
 */
public interface RoutingRule {

    /**
     * Evaluates this rule against the given refund request.
     *
     * @param request the inbound refund routing request; never {@code null}
     * @return a {@link RoutingDecision} if this rule applies, or {@code null}
     *         to pass to the next rule in the chain
     */
    RoutingDecision evaluate(RefundRequest request);

    /**
     * Human-readable name for this rule.
     * Used in {@link RoutingDecision#appliedRule} and structured log output.
     */
    String name();
}