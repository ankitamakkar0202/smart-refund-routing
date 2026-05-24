package com.refund.routing.model;

/**
 * The original payment method used for the transaction being refunded.
 *
 * <p>Used by {@code OriginalPaymentMethodRule} to assess whether the original
 * instrument is a viable refund target.
 */
public enum PaymentMethod {

    /** Credit card (Visa, Mastercard, Amex, etc.). */
    CREDIT_CARD,

    /** Debit card linked to a bank account. */
    DEBIT_CARD,

    /** Unified Payments Interface — instant bank-to-bank transfer. */
    UPI,

    /** Internet / net banking transfer. */
    NET_BANKING,

    /** Digital wallet (Paytm, PhonePe, Google Pay wallet, etc.). */
    WALLET,

    /** Payment method could not be determined; treat as unavailable. */
    UNKNOWN
}