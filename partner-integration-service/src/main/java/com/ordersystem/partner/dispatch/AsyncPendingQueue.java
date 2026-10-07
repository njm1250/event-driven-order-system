package com.ordersystem.partner.dispatch;

import com.ordersystem.partner.PartnerTask;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory queue per order for the asyncAcks mode. Only the head of each order may run, and a
 * rebalance discards everything because the new owner will receive the same records again.
 */
@Component
public class AsyncPendingQueue {
    private final Map<String, Deque<PartnerTask>> pending = new LinkedHashMap<>();
    private long generation;

    public synchronized void add(PartnerTask task) {
        task.epoch(generation);
        pending.computeIfAbsent(task.key(), k -> new ArrayDeque<>()).add(task);
    }

    public synchronized List<PartnerTask> heads() {
        List<PartnerTask> heads = new ArrayList<>();
        for (var queue : pending.values()) if (!queue.isEmpty()) heads.add(queue.peekFirst());
        return heads;
    }

    public synchronized void completed(PartnerTask task) {
        var queue = pending.get(task.key());
        if (queue == null || queue.peekFirst() != task) return;
        queue.removeFirst();
        if (queue.isEmpty()) pending.remove(task.key());
    }

    /** Acks from an older assignment must not be sent after a revoke. */
    public synchronized boolean isCurrent(PartnerTask task) {
        return task.epoch() == generation;
    }

    public synchronized void revokeAll() {
        generation++;
        pending.clear();
    }

    public synchronized int size() {
        return pending.values().stream().mapToInt(Deque::size).sum();
    }
}
