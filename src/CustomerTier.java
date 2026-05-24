/**
 * Loyalty / value tier of the customer requesting the refund.
 *
 * <p>Higher-tier customers receive preferential routing (instant channels,
 * more retry attempts) as decided by {@link VipCustomerRule}.
 */
public enum CustomerTier {

    /** Default tier — cost-optimised routing via weighted channel scoring. */
    STANDARD,

    /** High-value / loyal customer — routed to instant channels first. */
    VIP,

    /** Top-tier — same fast-path as VIP; kept separate for future rule tuning. */
    PLATINUM;

    /**
     * Returns {@code true} if this tier qualifies for the VIP fast-path.
     * Used by {@link VipCustomerRule} to avoid a switch-statement at the call site.
     */
    public boolean isPreferred() {
        return this == VIP || this == PLATINUM;
    }
}
