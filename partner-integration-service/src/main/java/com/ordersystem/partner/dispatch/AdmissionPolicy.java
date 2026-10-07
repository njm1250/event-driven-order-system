package com.ordersystem.partner.dispatch;

import com.ordersystem.partner.PartnerTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Decides whether a worker may start a task now. Encodes the isolation rules: a global worker
 * limit, at most one running operation per order, a per-seller concurrency cap, and a per-seller
 * retry budget so one failing seller cannot spend every worker on retries.
 */
public final class AdmissionPolicy {
    public record Admission(boolean admitted, boolean retry, long at) {
        static final Admission REJECTED = new Admission(false, false, 0);
    }

    private final int workers;
    private final int sellerConcurrency;
    private final int finishedLimit;
    private final RollingWindowBudget retryBudget;
    private final LongSupplier clock;
    private final Set<String> activeKeys = new HashSet<>();
    private final Map<String, Integer> activeSellers = new HashMap<>();
    // Candidate lists are snapshots; a task can finish between the snapshot and admission.
    private final LinkedHashSet<String> finished = new LinkedHashSet<>();
    private int active;

    public AdmissionPolicy(int workers, int sellerConcurrency, int retryBudget, long retryWindowMs,
                           int finishedLimit, LongSupplier clock) {
        this.workers = workers;
        this.sellerConcurrency = sellerConcurrency;
        this.finishedLimit = finishedLimit;
        this.retryBudget = new RollingWindowBudget(retryBudget, retryWindowMs);
        this.clock = clock;
    }

    public synchronized Admission tryAdmit(PartnerTask task) {
        // Read the clock inside the monitor so the retry window reflects the real admission order.
        long now = clock.getAsLong();
        String seller = task.sellerId();
        if (active >= workers
                || activeKeys.contains(task.key())
                || activeSellers.getOrDefault(seller, 0) >= sellerConcurrency
                || task.nextAt() > now
                || finished.contains(task.eventId())) {
            return Admission.REJECTED;
        }
        boolean retry = task.attempts() > 0;
        if (retry && !retryBudget.tryAcquire(seller, now)) return Admission.REJECTED;
        active++;
        activeKeys.add(task.key());
        activeSellers.merge(seller, 1, Integer::sum);
        return new Admission(true, retry, now);
    }

    public synchronized void markFinished(String eventId) {
        finished.add(eventId);
        if (finished.size() > finishedLimit) finished.remove(finished.iterator().next());
    }

    public synchronized void release(PartnerTask task) {
        active--;
        activeKeys.remove(task.key());
        activeSellers.merge(task.sellerId(), -1, Integer::sum);
    }

    public synchronized int active() {
        return active;
    }

    public synchronized Map<String, Integer> activeBySeller() {
        return new HashMap<>(activeSellers);
    }
}
