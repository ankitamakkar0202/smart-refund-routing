import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SlidingWindowRateLimiter {

    private static final int DEFAULT_LIMIT = 100;
    private static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, UserBucket> userBuckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public SlidingWindowRateLimiter() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MS);
    }

    public SlidingWindowRateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleaner");
            t.setDaemon(true);
            return t;
        });
        // Run cleanup once per window; buckets idle longer than one window have
        // no live timestamps and can be safely dropped.
        cleaner.scheduleAtFixedRate(this::evictStaleBuckets, windowMs, windowMs, TimeUnit.MILLISECONDS);
    }

    public boolean isAllowed(String userId) {
        // computeIfAbsent is atomic — safe under concurrent calls for the same userId
        UserBucket bucket = userBuckets.computeIfAbsent(userId, id -> new UserBucket(limit, windowMs));
        return bucket.tryAcquire();
    }

    public int getCurrentCount(String userId) {
        UserBucket bucket = userBuckets.get(userId);
        return bucket == null ? 0 : bucket.currentCount();
    }

    /**
     * Shuts down the background cleanup thread. Call this when the limiter is
     * no longer needed (e.g., in application shutdown hooks or tests).
     */
    public void close() {
        cleaner.shutdown();
    }

    // Remove buckets that have been idle for longer than one full window.
    // Any bucket inactive for windowMs is guaranteed to hold no live timestamps,
    // so removal is safe: the next isAllowed for that user will create a fresh bucket.
    private void evictStaleBuckets() {
        long cutoff = System.currentTimeMillis() - windowMs;
        // Two-condition guard: lastAccessTime must be stale AND the bucket must
        // hold no live timestamps. currentCount() acquires the bucket's lock and
        // evicts expired entries before counting, so a non-zero result means a
        // real in-window request exists and the bucket must be kept.
        userBuckets.entrySet().removeIf(e -> {
            UserBucket b = e.getValue();
            return b.getLastAccessTime() < cutoff && b.currentCount() == 0;
        });
    }
}