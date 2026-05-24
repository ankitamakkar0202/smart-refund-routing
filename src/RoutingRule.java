/**
 * Contract for a single routing rule in the chain-of-responsibility.
 *
 * <p>SOLID alignment:
 * <ul>
 *   <li><b>OCP</b> — new routing criteria are added by implementing this interface,
 *       never by modifying {@link RefundRoutingEngine}.</li>
 *   <li><b>LSP</b> — every implementation must honour the contract: return a
 *       non-null {@link RoutingDecision} if the rule applies, or {@code null}
 *       to pass control to the next rule. Never throw for a "rule doesn't apply"
 *       case — only throw for genuinely unexpected failures.</li>
 *   <li><b>ISP</b> — the interface is intentionally narrow (two methods) so
 *       implementations depend only on what they actually use.</li>
 * </ul>
 *
 * <p>Thread safety: implementations must be safe for concurrent calls from the
 * HTTP worker thread pool. Because all mutable state lives in {@link ChannelRegistry}
 * (which is itself thread-safe) and {@link RefundRoutingConfig} (read-only after
 * construction), stateless rule implementations are the expected norm.
 */
public interface RoutingRule {

    /**
     * Evaluates this rule against the given refund request.
     *
     * @param request the inbound refund routing request; never {@code null}
     * @return a {@link RoutingDecision} if this rule applies and produces a
     *         decision, or {@code null} to pass to the next rule in the chain
     */
    RoutingDecision evaluate(RefundRequest request);

    /**
     * Human-readable name for this rule.
     *
     * <p>Used in {@link RoutingDecision#appliedRule} and structured log output
     * so operators can trace exactly which rule drove each routing decision.
     */
    String name();
}
