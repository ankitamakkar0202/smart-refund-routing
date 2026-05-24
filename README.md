# Smart Refund Routing System

A lightweight Java microservice that dynamically decides **how** and **via which channel** a customer refund should be processed. Designed to plug into payment platforms (Razorpay, Paytm, GPay, etc.) as an independent routing layer.

---

## Overview

Traditional payment platforms route refunds statically — always back to the original method, or always via bank transfer. This service makes the decision **dynamically** by weighing:

- Customer loyalty tier (VIP gets instant channels)
- Refund amount (large amounts go to reliable bank channels)
- Payment method availability (graceful fallback when original method is expired/unavailable)
- Live channel success rates and costs (weighted scoring)

Every routing decision also returns a **retry policy** — a ranked list of fallback channels and backoff timings the caller should use if the primary channel fails.

---

## Architecture

```
POST /api/v1/refund/route
         │
         ▼
 RefundRoutingServer          ← HTTP adapter (no business logic)
         │
         ▼
 RefundRoutingEngine
   ├─ Input Validation
   │    amount == 0   → NONE   (no refund needed)      HTTP 200
   │    amount < 0    → ERROR  (invalid amount)         HTTP 400
   │    blank IDs     → ERROR  (missing fields)         HTTP 400
   │
   ├─ Rate Limiter  (SlidingWindowRateLimiter, per merchantId)
   │    limit exceeded → ERROR (RATE_LIMITED)           HTTP 429
   │
   └─ Rule Chain (Chain of Responsibility)
         1. HighValueRule              amount > threshold → BANK_TRANSFER / MANUAL_REVIEW
         2. VipCustomerRule            VIP/PLATINUM       → WALLET_CREDIT / UPI
         3. OriginalPaymentMethodRule  available + reliable → ORIGINAL_PAYMENT_METHOD
         4. ChannelScoringRule         weighted score     → best available channel
         5. FallbackRule               safety net         → BANK_TRANSFER / MANUAL_REVIEW
```

---

## SOLID Principles Applied

| Principle | How it's implemented |
|---|---|
| **S** — Single Responsibility | Each class has one job: `StructuredLogger` only logs, `ChannelRegistry` only stores channel data, `RefundRoutingServer` only handles HTTP |
| **O** — Open/Closed | Add new routing criteria by creating a new `RoutingRule` implementation — `RefundRoutingEngine` never changes |
| **L** — Liskov Substitution | All `RoutingRule` implementations honour the contract: return a `RoutingDecision` if applicable, `null` to pass to the next rule |
| **I** — Interface Segregation | `RoutingRule` exposes only `evaluate()` and `name()` — no unused methods |
| **D** — Dependency Inversion | `RefundRoutingEngine` depends on the `RoutingRule` interface, not concrete rules. All dependencies injected via constructor |

---

## Routing Rules

Rules are evaluated in strict priority order. The first rule that applies wins.

| Priority | Rule | Triggers when | Routes to |
|---|---|---|---|
| 1 | `HighValueRule` | `amount > routing.high_value_threshold` (default 50 000) | `BANK_TRANSFER` (or `MANUAL_REVIEW` if bank is down) |
| 2 | `VipCustomerRule` | `customerTier` is `VIP` or `PLATINUM` | `WALLET_CREDIT` (or `UPI` if wallet is down) |
| 3 | `OriginalPaymentMethodRule` | `originalMethodAvailable=true` AND channel success rate > threshold | `ORIGINAL_PAYMENT_METHOD` |
| 4 | `ChannelScoringRule` | Always (weighted scorer across all available channels) | Highest-scoring channel; also handles `amount < 500` shortcut to `WALLET_CREDIT` |
| 5 | `FallbackRule` | Safety net — only reached if no channels are available for scoring | `BANK_TRANSFER` (or `MANUAL_REVIEW` if bank is down) |

### Scoring Formula

```
score = (successRate × 0.6) − (normalizedCost × 0.4)
normalizedCost = channel.costPerTxn / maxCostAcrossAvailableChannels
```

Default channel scores (all channels available):

| Channel | Success Rate | Cost | Score |
|---|---|---|---|
| WALLET_CREDIT | 0.95 | 2.0 | **0.517** ← winner |
| UPI | 0.92 | 3.0 | 0.472 |
| ORIGINAL_PAYMENT_METHOD | 0.88 | 5.0 | 0.395 |
| BANK_TRANSFER | 0.97 | 8.0 | 0.182 |
| MANUAL_REVIEW | 1.00 | 15.0 | 0.200 |

---

## Configuration

All tuneable values live in `refund-routing.properties` at the project root.
The system starts successfully even if the file is missing (hardcoded defaults apply).

```properties
# Retry Policy
retry.max_attempts=3
retry.initial_delay_ms=1000
retry.backoff_multiplier=2.0

# Rate Limiting (per merchant)
rate_limit.requests_per_window=20
rate_limit.window_ms=60000

# Routing Thresholds
routing.high_value_threshold=50000
routing.small_amount_threshold=500
routing.original_method_min_success_rate=0.85

# Server
server.port=8080
```

---

## API Reference

### `POST /api/v1/refund/route`

**Request body:**
```json
{
  "requestId":               "req-abc-123",
  "merchantId":              "merchant-42",
  "customerId":              "cust-999",
  "amount":                  12500.00,
  "customerTier":            "VIP",
  "originalPaymentMethod":   "CREDIT_CARD",
  "originalMethodAvailable": true,
  "transactionDate":         1716540000000
}
```

**Success response (200):**
```json
{
  "requestId":        "req-abc-123",
  "selectedChannel":  "WALLET_CREDIT",
  "reason":           "VIP customer — instant wallet credit",
  "appliedRule":      "VipCustomerRule",
  "channelScore":     0.0000,
  "transactionDate":  1716540000000,
  "decidedAtEpochMs": 1716547200000,
  "processingTimeMs": 7200000,
  "retryPolicy": {
    "maxAttempts":       3,
    "initialDelayMs":    1000,
    "backoffMultiplier": 2.0,
    "fallbackOrder":     ["UPI", "BANK_TRANSFER", "MANUAL_REVIEW"]
  }
}
```

**Zero-amount response (200):**
```json
{
  "selectedChannel": "NONE",
  "reason":          "No refund required — transaction amount is zero",
  "appliedRule":     "VALIDATION",
  "retryPolicy":     null
}
```

**Rate-limited response (429):**
```json
{
  "selectedChannel": "ERROR",
  "appliedRule":     "RATE_LIMITED",
  "reason":          "Rate limit exceeded for merchant 'merchant-42'."
}
```

---

### `GET /api/v1/channels`

Returns live metadata for all registered refund channels.

```json
[
  {"channel":"WALLET_CREDIT","successRate":0.95,"costPerTxn":2.0,"avgSettlementHrs":0.5,"available":true},
  {"channel":"UPI","successRate":0.92,"costPerTxn":3.0,"avgSettlementHrs":1.0,"available":true},
  {"channel":"ORIGINAL_PAYMENT_METHOD","successRate":0.88,"costPerTxn":5.0,"avgSettlementHrs":2.0,"available":true},
  {"channel":"BANK_TRANSFER","successRate":0.97,"costPerTxn":8.0,"avgSettlementHrs":24.0,"available":true},
  {"channel":"MANUAL_REVIEW","successRate":1.0,"costPerTxn":15.0,"avgSettlementHrs":48.0,"available":true}
]
```

---

### `GET /api/v1/health`

```json
{"status":"UP"}
```

---

## Edge Cases Handled

| Scenario | Behaviour |
|---|---|
| `amount == 0` | Returns `NONE` channel — "no refund needed" (HTTP 200) |
| `amount < 0` | Returns error (HTTP 400) |
| Blank `merchantId` / `customerId` | Returns error (HTTP 400) |
| Unknown `customerTier` / `paymentMethod` | Returns error (HTTP 400) |
| Merchant exceeds rate limit | Returns `RATE_LIMITED` error (HTTP 429) |
| Preferred channel unavailable | Rule falls back to next best option |
| All channels unavailable | `FallbackRule` routes to `BANK_TRANSFER` or `MANUAL_REVIEW` |
| Routing rule throws exception | Exception is caught and logged; chain continues to next rule |

---

## Retry Policy

Every successful routing decision includes a `retryPolicy` object for the caller to use if the chosen channel fails:

```
Attempt 1 → selectedChannel (no delay)
Attempt 2 → fallbackOrder[0], wait initialDelayMs
Attempt 3 → fallbackOrder[1], wait initialDelayMs × backoffMultiplier
```

The fallback channel list is pre-ranked by weighted score. Configure `retry.max_attempts` in `refund-routing.properties` to control how many attempts are made.

---

## Structured Logging

Every significant event emits one JSON line to stdout (errors to stderr):

```json
{"timestamp":"2026-05-24T10:30:00.123Z","level":"INFO","event":"refund_routed","requestId":"req-123","merchantId":"m1","selectedChannel":"WALLET_CREDIT","appliedRule":"VipCustomerRule","processingTimeMs":7200000}
```

| Event | Level | Description |
|---|---|---|
| `server_started` | INFO | Server started, port number logged |
| `refund_routed` | INFO | Successful routing decision made |
| `no_refund_required` | INFO | Zero-amount transaction, no channel needed |
| `rate_limit_exceeded` | WARN | Merchant exceeded per-window limit |
| `invalid_request` | WARN | Validation failure (bad amount, missing fields) |
| `rule_skipped` | DEBUG | A rule returned null, chain continues |
| `channel_unavailable` | WARN | A preferred channel is marked unavailable |
| `rule_exception` | ERROR | A routing rule threw an unexpected exception |

---

## Compile & Run

**Requirements:** JDK 11 or higher. No external dependencies.

```bash
# 1. Compile (from project root)
javac -d out \
  src/UserBucket.java \
  src/SlidingWindowRateLimiter.java \
  src/RefundRoutingConfig.java \
  src/StructuredLogger.java \
  src/RefundChannel.java \
  src/CustomerTier.java \
  src/PaymentMethod.java \
  src/ChannelMetadata.java \
  src/RetryPolicy.java \
  src/RefundRequest.java \
  src/RoutingDecision.java \
  src/RoutingRule.java \
  src/HighValueRule.java \
  src/VipCustomerRule.java \
  src/OriginalPaymentMethodRule.java \
  src/ChannelScoringRule.java \
  src/FallbackRule.java \
  src/ChannelRegistry.java \
  src/RefundRoutingEngine.java \
  src/RefundRoutingServer.java \
  src/RefundRoutingTest.java

# 2. Run tests
java -cp out RefundRoutingTest

# 3. Start the server
java -cp out RefundRoutingServer

# 4. Smoke test
curl -s http://localhost:8080/api/v1/health

curl -s -X POST http://localhost:8080/api/v1/refund/route \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "req-001",
    "merchantId": "merchant-42",
    "customerId": "cust-999",
    "amount": 5000,
    "customerTier": "VIP",
    "originalPaymentMethod": "CREDIT_CARD",
    "originalMethodAvailable": true,
    "transactionDate": 1716540000000
  }'

curl -s http://localhost:8080/api/v1/channels
```

---

## Project Structure

```
smart-refund-routing/
├── README.md
├── refund-routing.properties          ← runtime configuration
└── src/
    ├── RefundRoutingConfig.java       ← loads .properties, typed getters
    ├── StructuredLogger.java          ← JSONL structured logging
    ├── RefundChannel.java             ← enum: WALLET_CREDIT, UPI, ...
    ├── CustomerTier.java              ← enum: STANDARD, VIP, PLATINUM
    ├── PaymentMethod.java             ← enum: CREDIT_CARD, UPI, ...
    ├── ChannelMetadata.java           ← immutable channel snapshot + score()
    ├── RetryPolicy.java               ← retry config + fallback order
    ├── RefundRequest.java             ← request DTO + fromJson()
    ├── RoutingDecision.java           ← response DTO + toJson()
    ├── RoutingRule.java               ← chain-of-responsibility interface
    ├── HighValueRule.java             ← rule 1: large amounts
    ├── VipCustomerRule.java           ← rule 2: VIP/PLATINUM tier
    ├── OriginalPaymentMethodRule.java ← rule 3: original instrument
    ├── ChannelScoringRule.java        ← rule 4: weighted scorer + retry policy
    ├── FallbackRule.java              ← rule 5: safety net
    ├── ChannelRegistry.java           ← thread-safe channel metadata store
    ├── RefundRoutingEngine.java       ← orchestrator
    ├── RefundRoutingServer.java       ← HTTP server (JDK built-in)
    └── RefundRoutingTest.java         ← 18 tests
```
