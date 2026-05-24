package com.refund.routing.ratelimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window rate limiter keyed by a user/merchant identifier.
 *
 * <p>Each key gets its own {@link UserBucket} which tracks timestamps of
 * requests within the current window. Expired buckets are cleaned up
 * periodically by a background daemon thread.
 */
public class SlidingWindowRateLimiter {

    private static final int  DEFAULT_LIMIT     = 100;
    private static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, UserBucket> userBuckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public SlidingWindowRateLimiter() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MS);
    }

    public SlidingWindowRateLimiter(int limit, long windowMs) {
        this.limit    = limit;
        this.windowMs = windowMs;
        this.cleaner  = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::evictStaleBuckets, windowMs, windowMs, TimeUnit.MILLISECONDS);
    }

    public boolean isAllowed(String userId) {
        UserBucket bucket = userBuckets.computeIfAbsent(userId, id -> new UserBucket(limit, windowMs));
        return bucket.tryAcquire();
    }

    public int getCurrentCount(String userId) {
        UserBucket bucket = userBuckets.get(userId);
        return bucket == null ? 0 : bucket.currentCount();
    }

    /** Shuts down the background cleanup thread. */
    public void close() {
        cleaner.shutdown();
    }

    private void evictStaleBuckets() {
        long cutoff = System.currentTimeMillis() - windowMs;
        userBuckets.entrySet().removeIf(e -> {
            UserBucket b = e.getValue();
            return b.getLastAccessTime() < cutoff && b.currentCount() == 0;
        });
    }
}