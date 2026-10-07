package com.ordersystem.partner.dispatch;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** At most {@code limit} acquisitions per key in any {@code windowMs}. Not thread-safe. */
final class RollingWindowBudget {
    private final int limit;
    private final long windowMs;
    private final Map<String, Deque<Long>> usage = new HashMap<>();

    RollingWindowBudget(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    boolean tryAcquire(String key, long now) {
        Deque<Long> times = usage.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() <= now - windowMs) times.removeFirst();
        if (times.size() >= limit) return false;
        times.addLast(now);
        return true;
    }
}
