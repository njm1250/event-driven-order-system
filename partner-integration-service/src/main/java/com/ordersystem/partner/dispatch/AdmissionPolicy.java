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
 *
 * In the inbox mode the seller cap and retry budget live in the database, shared by every
 * instance, and only the local part (workers, one operation per order) is checked here.
 */
public final class AdmissionPolicy {
    public enum Reason { ADMITTED, GLOBAL_FULL, ORDER_BUSY, SELLER_FULL, NOT_DUE, FINISHED, RETRY_BUDGET }

    public record Admission(boolean admitted, boolean retry, long at, Reason reason) {
        static Admission rejected(Reason reason) {
            return new Admission(false, false, 0, reason);
        }
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

    public Admission tryAdmit(PartnerTask task) {
        return tryAdmit(task, true);
    }

    /** Workers and one operation per order only; the caller enforces seller and retry limits. */
    public Admission tryAdmitLocal(PartnerTask task) {
        return tryAdmit(task, false);
    }

    private synchronized Admission tryAdmit(PartnerTask task, boolean sellerLimits) {
        // Read the clock inside the monitor so the retry window reflects the real admission order.
        long now = clock.getAsLong();
        String seller = task.sellerId();
        if (active >= workers) return Admission.rejected(Reason.GLOBAL_FULL);
        if (activeKeys.contains(task.key())) return Admission.rejected(Reason.ORDER_BUSY);
        if (sellerLimits && activeSellers.getOrDefault(seller, 0) >= sellerConcurrency) return Admission.rejected(Reason.SELLER_FULL);
        if (task.nextAt() > now) return Admission.rejected(Reason.NOT_DUE);
        if (finished.contains(task.eventId())) return Admission.rejected(Reason.FINISHED);
        // A retry is an admission after a partner call may already have started for this event.
        boolean retry = task.attempts() > 0;
        if (sellerLimits && retry && !retryBudget.tryAcquire(seller, now)) return Admission.rejected(Reason.RETRY_BUDGET);
        active++;
        activeKeys.add(task.key());
        activeSellers.merge(seller, 1, Integer::sum);
        return new Admission(true, retry, now, Reason.ADMITTED);
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
