package com.ordersystem.partner.dispatch;

import com.ordersystem.partner.PartnerTask;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory queue per order for the asyncAcks mode. Only the head of each order may run. A revoke
 * discards the queued work of the revoked partitions only, because the new owner will receive the
 * same records again; partitions this consumer keeps must not lose their pending acks.
 */
@Component
public class AsyncPendingQueue {
    private final Map<String, Deque<PartnerTask>> pending = new LinkedHashMap<>();
    private final Map<Integer, Long> generations = new HashMap<>();

    public synchronized void add(PartnerTask task) {
        task.epoch(generations.getOrDefault(task.partition(), 0L));
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

    /** Acks from an older assignment of the task's partition must not be sent after a revoke. */
    public synchronized boolean isCurrent(PartnerTask task) {
        return task.epoch() == generations.getOrDefault(task.partition(), 0L);
    }

    public synchronized void revoke(Collection<Integer> partitions) {
        for (int partition : partitions) generations.merge(partition, 1L, Long::sum);
        var queues = pending.values().iterator();
        while (queues.hasNext()) {
            var queue = queues.next();
            queue.removeIf(task -> partitions.contains(task.partition()));
            if (queue.isEmpty()) queues.remove();
        }
    }

    public synchronized int size() {
        return pending.values().stream().mapToInt(Deque::size).sum();
    }
}
