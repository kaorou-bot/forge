package forge.relay;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Small fixed-window limiter for lobby control operations. */
final class IpRateLimiter {
    enum Action {
        REGISTER,
        LIST,
        JOIN
    }

    private static final int DEFAULT_REGISTER_LIMIT = 10;
    private static final int DEFAULT_LIST_LIMIT = 120;
    private static final int DEFAULT_JOIN_LIMIT = 60;
    private static final long DEFAULT_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Map<Key, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final int registerLimit;
    private final int listLimit;
    private final int joinLimit;
    private final long windowNanos;

    IpRateLimiter() {
        this(DEFAULT_REGISTER_LIMIT, DEFAULT_LIST_LIMIT, DEFAULT_JOIN_LIMIT,
                DEFAULT_WINDOW_NANOS);
    }

    IpRateLimiter(int registerLimit, int listLimit, int joinLimit, long windowNanos) {
        this.registerLimit = registerLimit;
        this.listLimit = listLimit;
        this.joinLimit = joinLimit;
        this.windowNanos = windowNanos;
    }

    boolean allow(InetAddress address, Action action) {
        long now = System.nanoTime();
        boolean allowed = counters.computeIfAbsent(new Key(address, action),
                ignored -> new Counter(now)).acquire(now, limit(action), windowNanos);
        if ((requests.incrementAndGet() & 1_023) == 0) {
            counters.entrySet().removeIf(entry -> entry.getValue().expired(now, windowNanos));
        }
        return allowed;
    }

    private int limit(Action action) {
        return switch (action) {
            case REGISTER -> registerLimit;
            case LIST -> listLimit;
            case JOIN -> joinLimit;
        };
    }

    private record Key(InetAddress address, Action action) {
    }

    private static final class Counter {
        private long windowStarted;
        private int count;

        Counter(long now) {
            windowStarted = now;
        }

        synchronized boolean acquire(long now, int limit, long windowNanos) {
            if (now - windowStarted >= windowNanos) {
                windowStarted = now;
                count = 0;
            }
            count++;
            return count <= limit;
        }

        synchronized boolean expired(long now, long windowNanos) {
            return now - windowStarted >= windowNanos * 2;
        }
    }
}
