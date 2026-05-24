package com.refund.routing;

import com.refund.routing.config.RefundRoutingConfig;
import com.refund.routing.engine.RefundRoutingEngine;
import com.refund.routing.model.CustomerTier;
import com.refund.routing.model.PaymentMethod;
import com.refund.routing.model.RefundChannel;
import com.refund.routing.model.RefundRequest;
import com.refund.routing.model.RoutingDecision;
import com.refund.routing.ratelimiter.SlidingWindowRateLimiter;
import com.refund.routing.registry.ChannelMetadata;
import com.refund.routing.registry.ChannelRegistry;

/**
 * Test suite for the Smart Refund Routing System.
 *
 * <p>18 tests covering: high-value routing, VIP routing, original method routing,
 * channel scoring, fallback, rate limiting, input validation, and retry policy.
 * No external testing library required — run via {@code main()}.
 */
public class RefundRoutingTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final RefundRoutingConfig CONFIG = new RefundRoutingConfig();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Smart Refund Routing System — Test Suite ===\n");

        // ── HighValueRule ──────────────────────────────────────────────────────
        testHighValueGoesToBankTransfer();
        testHighValueGoesToManualReviewWhenBankDown();

        // ── VipCustomerRule ────────────────────────────────────────────────────
        testVipGetsWalletCredit();
        testVipGetsUpiWhenWalletDown();
        testPlatinumGetsWalletCredit();
        testStandardNotAffectedByVipRule();

        // ── OriginalPaymentMethodRule ──────────────────────────────────────────
        testOriginalMethodUsedWhenAvailableAndHighSuccessRate();
        testOriginalMethodSkippedWhenLowSuccessRate();
        testOriginalMethodSkippedWhenUnavailable();

        // ── ChannelScoringRule ─────────────────────────────────────────────────
        testSmallAmountGoesToWallet();
        testScoringPicksBestChannel();
        testRetryPolicyFallbackOrderIsRanked();

        // ── FallbackRule ───────────────────────────────────────────────────────
        testFallbackWhenAllChannelsUnavailable();

        // ── Rule chain priority ────────────────────────────────────────────────
        testHighValueOverridesVip();

        // ── Rate limiting ──────────────────────────────────────────────────────
        testRateLimitBlocksAfterLimit();
        testRateLimitIsolatedPerMerchant();

        // ── Input validation ───────────────────────────────────────────────────
        testZeroAmountReturnsNoRefundNeeded();
        testNegativeAmountReturnsError();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // ─── HighValueRule ────────────────────────────────────────────────────────

    static void testHighValueGoesToBankTransfer() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r1", "m1", "c1", 75_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("high-value should go to BANK_TRANSFER",
                RefundChannel.BANK_TRANSFER, d.selectedChannel);
        assertEquals("applied rule should be HighValueRule",
                "HighValueRule", d.appliedRule);
    }

    static void testHighValueGoesToManualReviewWhenBankDown() {
        ChannelRegistry registry = defaultRegistry();
        registry.setAvailability(RefundChannel.BANK_TRANSFER, false);
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r2", "m1", "c1", 75_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("high-value should escalate to MANUAL_REVIEW when bank is down",
                RefundChannel.MANUAL_REVIEW, d.selectedChannel);
    }

    // ─── VipCustomerRule ──────────────────────────────────────────────────────

    static void testVipGetsWalletCredit() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r3", "m1", "c1", 5_000.0,
                CustomerTier.VIP, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("VIP should get WALLET_CREDIT", RefundChannel.WALLET_CREDIT, d.selectedChannel);
        assertEquals("applied rule should be VipCustomerRule", "VipCustomerRule", d.appliedRule);
    }

    static void testVipGetsUpiWhenWalletDown() {
        ChannelRegistry registry = defaultRegistry();
        registry.setAvailability(RefundChannel.WALLET_CREDIT, false);
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r4", "m1", "c1", 5_000.0,
                CustomerTier.VIP, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("VIP should fall back to UPI when wallet is down",
                RefundChannel.UPI, d.selectedChannel);
        assertEquals("applied rule should be VipCustomerRule", "VipCustomerRule", d.appliedRule);
    }

    static void testPlatinumGetsWalletCredit() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r5", "m1", "c1", 5_000.0,
                CustomerTier.PLATINUM, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("PLATINUM should also get WALLET_CREDIT",
                RefundChannel.WALLET_CREDIT, d.selectedChannel);
    }

    static void testStandardNotAffectedByVipRule() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r6", "m1", "c1", 5_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertNotEquals("STANDARD customer must not go through VipCustomerRule",
                "VipCustomerRule", d.appliedRule);
    }

    // ─── OriginalPaymentMethodRule ────────────────────────────────────────────

    static void testOriginalMethodUsedWhenAvailableAndHighSuccessRate() {
        ChannelRegistry registry = defaultRegistry();
        registry.register(new ChannelMetadata(RefundChannel.ORIGINAL_PAYMENT_METHOD,
                0.92, 5.0, 2.0, true));
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r7", "m1", "c1", 10_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, true, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("original method should be used when available and reliable",
                RefundChannel.ORIGINAL_PAYMENT_METHOD, d.selectedChannel);
        assertEquals("applied rule should be OriginalPaymentMethodRule",
                "OriginalPaymentMethodRule", d.appliedRule);
    }

    static void testOriginalMethodSkippedWhenLowSuccessRate() {
        ChannelRegistry registry = defaultRegistry();
        registry.register(new ChannelMetadata(RefundChannel.ORIGINAL_PAYMENT_METHOD,
                0.80, 5.0, 2.0, true));
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r8", "m1", "c1", 10_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, true, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertNotEquals("original method should be skipped when success rate is low",
                "OriginalPaymentMethodRule", d.appliedRule);
    }

    static void testOriginalMethodSkippedWhenUnavailable() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r9", "m1", "c1", 10_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertNotEquals("original method rule should be skipped when instrument is unavailable",
                "OriginalPaymentMethodRule", d.appliedRule);
    }

    // ─── ChannelScoringRule ───────────────────────────────────────────────────

    static void testSmallAmountGoesToWallet() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r10", "m1", "c1", 200.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("small amount should go to WALLET_CREDIT",
                RefundChannel.WALLET_CREDIT, d.selectedChannel);
        assertEquals("applied rule should be ChannelScoringRule",
                "ChannelScoringRule", d.appliedRule);
    }

    static void testScoringPicksBestChannel() {
        ChannelRegistry registry = new ChannelRegistry();
        registry.setAvailability(RefundChannel.UPI, false);
        registry.setAvailability(RefundChannel.ORIGINAL_PAYMENT_METHOD, false);
        registry.setAvailability(RefundChannel.MANUAL_REVIEW, false);

        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r11", "m1", "c1", 10_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("scorer should pick WALLET_CREDIT as the highest-scoring channel",
                RefundChannel.WALLET_CREDIT, d.selectedChannel);
        assertEquals("applied rule should be ChannelScoringRule",
                "ChannelScoringRule", d.appliedRule);
        assertTrue("channelScore should be positive", d.channelScore > 0.0);
    }

    static void testRetryPolicyFallbackOrderIsRanked() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r12", "m1", "c1", 10_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertTrue("scoring rule should produce a non-null RetryPolicy",
                d.retryPolicy != null);
        assertTrue("fallback order should not be empty",
                d.retryPolicy != null && !d.retryPolicy.fallbackOrder.isEmpty());
        boolean primaryInFallback = d.retryPolicy != null &&
                d.retryPolicy.fallbackOrder.contains(d.selectedChannel);
        assertFalse("primary channel must not appear in fallbackOrder", primaryInFallback);
    }

    // ─── FallbackRule ─────────────────────────────────────────────────────────

    static void testFallbackWhenAllChannelsUnavailable() {
        ChannelRegistry registry = new ChannelRegistry();
        registry.setAvailability(RefundChannel.WALLET_CREDIT,           false);
        registry.setAvailability(RefundChannel.UPI,                     false);
        registry.setAvailability(RefundChannel.ORIGINAL_PAYMENT_METHOD, false);
        registry.setAvailability(RefundChannel.BANK_TRANSFER,           false);
        registry.setAvailability(RefundChannel.MANUAL_REVIEW,           false);

        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r13", "m1", "c1", 10_000.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("fallback should escalate to MANUAL_REVIEW when bank is also down",
                RefundChannel.MANUAL_REVIEW, d.selectedChannel);
        assertEquals("applied rule should be FallbackRule", "FallbackRule", d.appliedRule);
    }

    // ─── Rule chain priority ──────────────────────────────────────────────────

    static void testHighValueOverridesVip() {
        ChannelRegistry registry = defaultRegistry();
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, registry);

        RefundRequest req = request("r14", "m1", "c1", 75_000.0,
                CustomerTier.VIP, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("HighValueRule must fire before VipCustomerRule",
                "HighValueRule", d.appliedRule);
        assertEquals("high-value VIP should go to BANK_TRANSFER, not WALLET_CREDIT",
                RefundChannel.BANK_TRANSFER, d.selectedChannel);
    }

    // ─── Rate limiting ────────────────────────────────────────────────────────

    static void testRateLimitBlocksAfterLimit() throws InterruptedException {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, 1_000L);
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, defaultRegistry(), limiter);

        int allowed = 0;
        RoutingDecision lastDecision = null;
        for (int i = 0; i < 4; i++) {
            RefundRequest req = request("r15-" + i, "merchant-rl", "c1", 1_000.0,
                    CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());
            lastDecision = engine.route(req);
            if (lastDecision.selectedChannel != RefundChannel.ERROR) allowed++;
        }
        engine.close();

        assertEquals("exactly 3 of 4 requests should be allowed", 3, allowed);
        assertTrue("4th request should be RATE_LIMITED",
                lastDecision != null && "RATE_LIMITED".equals(lastDecision.appliedRule));
    }

    static void testRateLimitIsolatedPerMerchant() throws InterruptedException {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 1_000L);
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, defaultRegistry(), limiter);

        engine.route(request("ra1", "merchant-A", "c1", 500.0,
                CustomerTier.STANDARD, PaymentMethod.UPI, false, past()));
        engine.route(request("ra2", "merchant-A", "c1", 500.0,
                CustomerTier.STANDARD, PaymentMethod.UPI, false, past()));

        RoutingDecision bDecision = engine.route(
                request("rb1", "merchant-B", "c1", 500.0,
                        CustomerTier.STANDARD, PaymentMethod.UPI, false, past()));

        engine.close();

        assertNotEquals("merchant-B should not be rate-limited by merchant-A's usage",
                "RATE_LIMITED", bDecision.appliedRule);
    }

    // ─── Input validation ──────────────────────────────────────────────────────

    static void testZeroAmountReturnsNoRefundNeeded() {
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, defaultRegistry());

        RefundRequest req = request("r16", "m1", "c1", 0.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("zero amount should return channel NONE",
                RefundChannel.NONE, d.selectedChannel);
        assertEquals("applied rule should be VALIDATION", "VALIDATION", d.appliedRule);
        assertNull("no retry policy for zero-amount decisions", d.retryPolicy);
    }

    static void testNegativeAmountReturnsError() {
        RefundRoutingEngine engine = new RefundRoutingEngine(CONFIG, defaultRegistry());

        RefundRequest req = request("r17", "m1", "c1", -500.0,
                CustomerTier.STANDARD, PaymentMethod.CREDIT_CARD, false, past());

        RoutingDecision d = engine.route(req);
        engine.close();

        assertEquals("negative amount should return ERROR channel",
                RefundChannel.ERROR, d.selectedChannel);
        assertEquals("applied rule should be VALIDATION_ERROR",
                "VALIDATION_ERROR", d.appliedRule);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static ChannelRegistry defaultRegistry() {
        return new ChannelRegistry();
    }

    private static RefundRequest request(String requestId, String merchantId,
                                         String customerId, double amount,
                                         CustomerTier tier, PaymentMethod method,
                                         boolean methodAvailable, long transactionDate) {
        return new RefundRequest(requestId, merchantId, customerId, amount,
                tier, method, methodAvailable, transactionDate);
    }

    private static long past() {
        return System.currentTimeMillis() - (2 * 60 * 60 * 1000L);
    }

    // ─── Assert helpers ───────────────────────────────────────────────────────

    private static void assertTrue(String msg, boolean condition) {
        if (condition) {
            System.out.println("  PASS  " + msg);
            passed++;
        } else {
            System.out.println("  FAIL  " + msg);
            failed++;
        }
    }

    private static void assertFalse(String msg, boolean condition) {
        assertTrue(msg, !condition);
    }

    private static void assertEquals(String msg, Object expected, Object actual) {
        boolean match = expected == null ? actual == null : expected.equals(actual);
        if (match) {
            System.out.println("  PASS  " + msg);
            passed++;
        } else {
            System.out.println("  FAIL  " + msg
                    + " [expected=" + expected + ", actual=" + actual + "]");
            failed++;
        }
    }

    private static void assertNotEquals(String msg, Object unexpected, Object actual) {
        boolean match = unexpected == null ? actual == null : unexpected.equals(actual);
        if (!match) {
            System.out.println("  PASS  " + msg);
            passed++;
        } else {
            System.out.println("  FAIL  " + msg + " [unexpectedly equal to: " + actual + "]");
            failed++;
        }
    }

    private static void assertNull(String msg, Object actual) {
        if (actual == null) {
            System.out.println("  PASS  " + msg);
            passed++;
        } else {
            System.out.println("  FAIL  " + msg + " [expected null, got: " + actual + "]");
            failed++;
        }
    }
}