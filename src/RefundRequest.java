/**
 * Immutable DTO representing an inbound refund routing request.
 *
 * <p>Parsed from the JSON body of {@code POST /api/v1/refund/route} by the
 * static {@link #fromJson(String)} method — no external JSON library required.
 *
 * <p>Field contract:
 * <ul>
 *   <li>{@code requestId}, {@code merchantId}, {@code customerId} — non-blank strings</li>
 *   <li>{@code amount} — numeric; 0 triggers "no refund needed"; negative is an error</li>
 *   <li>{@code customerTier} — one of {@link CustomerTier} enum names (case-insensitive)</li>
 *   <li>{@code originalPaymentMethod} — one of {@link PaymentMethod} enum names</li>
 *   <li>{@code originalMethodAvailable} — boolean</li>
 *   <li>{@code transactionDate} — epoch milliseconds when the original payment occurred</li>
 * </ul>
 */
public final class RefundRequest {

    public final String requestId;
    public final String merchantId;
    public final String customerId;
    public final double amount;
    public final CustomerTier customerTier;
    public final PaymentMethod originalPaymentMethod;
    public final boolean originalMethodAvailable;
    /** Epoch ms of the original transaction — used to compute processingTimeMs. */
    public final long transactionDate;

    public RefundRequest(String requestId, String merchantId, String customerId,
                         double amount, CustomerTier customerTier,
                         PaymentMethod originalPaymentMethod,
                         boolean originalMethodAvailable,
                         long transactionDate) {
        this.requestId               = requestId;
        this.merchantId              = merchantId;
        this.customerId              = customerId;
        this.amount                  = amount;
        this.customerTier            = customerTier;
        this.originalPaymentMethod   = originalPaymentMethod;
        this.originalMethodAvailable = originalMethodAvailable;
        this.transactionDate         = transactionDate;
    }

    // ─── JSON parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a {@link RefundRequest} from a raw JSON string.
     *
     * <p>Strategy: locate each field by searching for {@code "fieldName":} then
     * extract the token after the colon up to the next delimiter. No external libs.
     *
     * @throws IllegalArgumentException if any required field is missing or invalid
     */
    public static RefundRequest fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Request body is empty");
        }

        String requestId             = requireString(json, "requestId");
        String merchantId            = requireString(json, "merchantId");
        String customerId            = requireString(json, "customerId");
        double amount                = requireDouble(json, "amount");
        CustomerTier tier            = requireEnum(json, "customerTier", CustomerTier.class);
        PaymentMethod method         = requireEnum(json, "originalPaymentMethod", PaymentMethod.class);
        boolean methodAvailable      = requireBoolean(json, "originalMethodAvailable");
        long transactionDate         = requireLong(json, "transactionDate");

        if (requestId.isBlank())  throw new IllegalArgumentException("requestId must not be blank");
        if (merchantId.isBlank()) throw new IllegalArgumentException("merchantId must not be blank");
        if (customerId.isBlank()) throw new IllegalArgumentException("customerId must not be blank");

        return new RefundRequest(requestId, merchantId, customerId, amount,
                tier, method, methodAvailable, transactionDate);
    }

    // ─── Extraction helpers ───────────────────────────────────────────────────

    /** Extracts a quoted string value for the given JSON key. */
    private static String requireString(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx < 0) throw new IllegalArgumentException("Missing field: " + key);

        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx < 0) throw new IllegalArgumentException("Malformed JSON near field: " + key);

        // Skip whitespace after colon
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        if (start >= json.length() || json.charAt(start) != '"') {
            throw new IllegalArgumentException("Expected string value for field: " + key);
        }

        // Find closing quote, handling simple escaped quotes
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"')  { break;    }
            end++;
        }
        return json.substring(start + 1, end);
    }

    /** Extracts a numeric (double) value for the given JSON key. */
    private static double requireDouble(String json, String key) {
        String raw = extractBareValue(json, key);
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number for field '" + key + "': " + raw);
        }
    }

    /** Extracts a numeric (long) value for the given JSON key. */
    private static long requireLong(String json, String key) {
        String raw = extractBareValue(json, key);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for field '" + key + "': " + raw);
        }
    }

    /** Extracts a boolean value for the given JSON key. */
    private static boolean requireBoolean(String json, String key) {
        String raw = extractBareValue(json, key).toLowerCase();
        if ("true".equals(raw))  return true;
        if ("false".equals(raw)) return false;
        throw new IllegalArgumentException("Invalid boolean for field '" + key + "': " + raw);
    }

    /** Extracts an enum constant (case-insensitive) for the given JSON key. */
    private static <E extends Enum<E>> E requireEnum(String json, String key, Class<E> type) {
        String raw = requireString(json, key).toUpperCase().replace("-", "_").replace(" ", "_");
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value '" + raw + "' for field '" + key + "'. " +
                    "Expected one of: " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    /**
     * Extracts a bare (unquoted) token — used for numbers and booleans.
     * The token ends at the next {@code ,}, {@code }}, or whitespace.
     */
    private static String extractBareValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx < 0) throw new IllegalArgumentException("Missing field: " + key);

        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx < 0) throw new IllegalArgumentException("Malformed JSON near field: " + key);

        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            end++;
        }
        String raw = json.substring(start, end).trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Missing value for field: " + key);
        return raw;
    }

    @Override
    public String toString() {
        return "RefundRequest{requestId='" + requestId + "', merchantId='" + merchantId
                + "', customerId='" + customerId + "', amount=" + amount
                + ", customerTier=" + customerTier
                + ", originalPaymentMethod=" + originalPaymentMethod
                + ", originalMethodAvailable=" + originalMethodAvailable
                + ", transactionDate=" + transactionDate + "}";
    }
}
