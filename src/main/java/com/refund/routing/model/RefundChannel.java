package com.refund.routing.model;

/**
 * The channel through which a refund will be processed.
 *
 * <p>{@code NONE} is a sentinel returned when no refund is needed (zero-amount
 * transactions). {@code ERROR} is used only in error/rate-limited decisions and
 * is never routed to an actual payment channel.
 */
public enum RefundChannel {

    /** Instant credit to the customer's in-app wallet. Lowest settlement time. */
    WALLET_CREDIT,

    /** Refund via Unified Payments Interface. Near-instant settlement. */
    UPI,

    /** Refund to the original payment instrument (card, net banking, etc.). */
    ORIGINAL_PAYMENT_METHOD,

    /** Standard bank transfer (NEFT / IMPS). Higher reliability, slower. */
    BANK_TRANSFER,

    /** Manual review queue — used for very high-value or flagged transactions. */
    MANUAL_REVIEW,

    /** Sentinel: transaction amount is zero; no refund channel is needed. */
    NONE,

    /** Sentinel: an error occurred (rate limit, validation failure, etc.). */
    ERROR
}