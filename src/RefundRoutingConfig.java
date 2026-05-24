import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads configuration from {@code refund-routing.properties}.
 *
 * <p>SRP: single job — read config and expose typed values.
 * DIP: all other classes receive this via constructor injection; none reach for
 * config statically.
 *
 * <p>The file is searched in this order:
 * <ol>
 *   <li>Classpath resource {@code /refund-routing.properties}</li>
 *   <li>File system path {@code refund-routing.properties} (working directory)</li>
 * </ol>
 * If the file is missing, all getters return their documented defaults so the
 * system always boots with sensible values.
 */
public final class RefundRoutingConfig {

    private final Properties props = new Properties();

    public RefundRoutingConfig() {
        // 1. Try classpath first (works when running from the `out/` directory)
        InputStream classpathStream = getClass().getResourceAsStream("/refund-routing.properties");
        if (classpathStream != null) {
            load(classpathStream, "classpath:/refund-routing.properties");
            return;
        }

        // 2. Fall back to current working directory
        try (InputStream fileStream = new FileInputStream("refund-routing.properties")) {
            load(fileStream, "file:refund-routing.properties");
        } catch (IOException e) {
            System.err.println("[RefundRoutingConfig] Config file not found — using all defaults.");
        }
    }

    private void load(InputStream in, String source) {
        try {
            props.load(in);
            System.out.println("[RefundRoutingConfig] Loaded config from " + source);
        } catch (IOException e) {
            System.err.println("[RefundRoutingConfig] Failed to read config from " + source
                    + ": " + e.getMessage() + " — using defaults.");
        }
    }

    // ─── Typed getters with defaults ──────────────────────────────────────────

    /** {@code retry.max_attempts} — default {@code 3} */
    public int getRetryMaxAttempts() {
        return getInt("retry.max_attempts", 3);
    }

    /** {@code retry.initial_delay_ms} — default {@code 1000} ms */
    public long getRetryInitialDelayMs() {
        return getLong("retry.initial_delay_ms", 1_000L);
    }

    /** {@code retry.backoff_multiplier} — default {@code 2.0} */
    public double getRetryBackoffMultiplier() {
        return getDouble("retry.backoff_multiplier", 2.0);
    }

    /** {@code rate_limit.requests_per_window} — default {@code 20} */
    public int getRateLimitRequestsPerWindow() {
        return getInt("rate_limit.requests_per_window", 20);
    }

    /** {@code rate_limit.window_ms} — default {@code 60 000} ms */
    public long getRateLimitWindowMs() {
        return getLong("rate_limit.window_ms", 60_000L);
    }

    /** {@code routing.high_value_threshold} — default {@code 50 000.0} */
    public double getHighValueThreshold() {
        return getDouble("routing.high_value_threshold", 50_000.0);
    }

    /** {@code routing.small_amount_threshold} — default {@code 500.0} */
    public double getSmallAmountThreshold() {
        return getDouble("routing.small_amount_threshold", 500.0);
    }

    /** {@code routing.original_method_min_success_rate} — default {@code 0.85} */
    public double getOriginalMethodMinSuccessRate() {
        return getDouble("routing.original_method_min_success_rate", 0.85);
    }

    /** {@code server.port} — default {@code 8080} */
    public int getServerPort() {
        return getInt("server.port", 8080);
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[RefundRoutingConfig] Bad int for '" + key + "': " + val + " — using default " + defaultValue);
            return defaultValue;
        }
    }

    private long getLong(String key, long defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[RefundRoutingConfig] Bad long for '" + key + "': " + val + " — using default " + defaultValue);
            return defaultValue;
        }
    }

    private double getDouble(String key, double defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[RefundRoutingConfig] Bad double for '" + key + "': " + val + " — using default " + defaultValue);
            return defaultValue;
        }
    }
}
