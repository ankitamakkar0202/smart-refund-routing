import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

public class UserBucket {

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<

            Long> timestamps = new ArrayDeque<>();
    private final int limit;
    private final long windowMs;
    // written under lock; read by cleanup thread without lock — volatile suffices
    private volatile long lastAccessTime;

    public UserBucket(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.lastAccessTime = System.currentTimeMillis();
    }

    public boolean tryAcquire() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            lastAccessTime = now;

            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMs) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public int currentCount() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMs) {
                timestamps.pollFirst();
            }
            return timestamps.size();
        } finally {
            lock.unlock();
        }
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }
}