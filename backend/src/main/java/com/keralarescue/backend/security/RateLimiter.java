package com.keralarescue.backend.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Fixed-window request counter per key. Thread-safe; old windows are pruned lazily. */
public final class RateLimiter {
    private final int maxPerWindow;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxPerWindow, long windowMillis) { this(maxPerWindow, windowMillis, System::currentTimeMillis); }

    public RateLimiter(int maxPerWindow, long windowMillis, LongSupplier clock) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** @return true if the call is allowed, false if the key is over its limit. */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (windows.size() > 20_000) prune(now);
        long[] w = windows.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= windowMillis) { w[0] = now; w[1] = 0; }
            if (w[1] >= maxPerWindow) return false;
            w[1]++;
            return true;
        }
    }

    public long retryAfterSeconds() { return Math.max(1, windowMillis / 1000); }

    private void prune(long now) { windows.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMillis); }
}
