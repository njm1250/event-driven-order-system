package com.ordersystem.partner.dispatch;

import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository;
import com.ordersystem.partner.inbox.InboxRepository.Claim;
import com.ordersystem.partner.inbox.InboxRepository.ClaimAttempt;
import com.ordersystem.partner.inbox.InboxRepository.ClaimResult;
import com.ordersystem.partner.inbox.LeaseKeeper;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import com.ordersystem.partner.processing.PartnerOrderProcessor.StaleClaimException;
import com.ordersystem.partner.processing.SellerCircuitBreakers;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Worker pool for the asyncAcks and inbox modes. Retry waits are stored as a time on the task or
 * inbox row instead of sleeping, so a failing seller never holds a worker while it waits.
 *
 * Dispatch runs as soon as a worker frees up or new work arrives; the 20ms tick only catches
 * retry times that come due; dispatching only on the tick would cap starts at the worker count per tick.
 *
 * Inbox: a row is claimed in the database only when a local worker is free, together with a seller
 * permit, so instances never queue work they cannot start and the seller limit holds across them.
 *
 * On shutdown it stops after the Kafka listener containers (lower phase), so intake has already
 * stopped; it then starts no new work and lets in-flight partner calls finish before the JVM exits.
 */
@Component
public class WorkerDispatcher implements SmartLifecycle {
    private final PartnerSettings settings;
    private final InboxRepository inbox;
    private final AsyncPendingQueue pending;
    private final AdmissionPolicy admission;
    private final PartnerOrderProcessor processor;
    private final SellerCircuitBreakers breakers;
    private final LeaseKeeper leases;
    private final TransactionTemplate tx;
    private final Tracer tracer;
    private final RetryBackoff backoff;
    private final ThreadPoolExecutor executor;
    private final ExecutorService wakeups = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dispatch-wakeup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private volatile boolean running = true;

    public WorkerDispatcher(PartnerSettings settings, InboxRepository inbox, AsyncPendingQueue pending,
                            AdmissionPolicy admission, PartnerOrderProcessor processor, SellerCircuitBreakers breakers,
                            LeaseKeeper leases, TransactionTemplate tx, Tracer tracer) {
        this.settings = settings;
        this.inbox = inbox;
        this.pending = pending;
        this.admission = admission;
        this.processor = processor;
        this.breakers = breakers;
        this.leases = leases;
        this.tx = tx;
        this.tracer = tracer;
        this.backoff = RetryBackoff.of(settings);
        this.executor = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.workers()));
    }

    /** Coalesces bursts of signals into one extra dispatch pass. */
    public void wake() {
        if (!settings.mode().usesWorkerPool() || !wakeRequested.compareAndSet(false, true)) return;
        wakeups.execute(() -> {
            wakeRequested.set(false);
            dispatch();
        });
    }

    @Scheduled(fixedDelay = 20)
    public synchronized void dispatch() {
        if (!settings.mode().usesWorkerPool() || !running) return;
        try {
            if (settings.mode() == ProcessingMode.INBOX) dispatchInbox();
            else dispatchAsync();
        } catch (Exception e) {
            tracer.trace("dispatch_error", null, "error", e.toString());
        }
    }

    private void dispatchAsync() {
        for (PartnerTask task : pending.heads()) {
            var decision = admission.tryAdmit(task);
            if (!decision.admitted()) continue;
            if (decision.retry()) {
                tracer.trace("retry_admitted", task, "admittedAt", decision.at(),
                        "windowMs", settings.retryWindowMs(), "budget", settings.retryBudget());
            }
            try {
                executor.execute(() -> run(task));
            } catch (RejectedExecutionException stopping) {
                admission.release(task);
            }
        }
    }

    private void dispatchInbox() throws Exception {
        int free = settings.workers() - admission.active();
        if (free <= 0) return;
        List<PartnerTask> candidates = inbox.findReady(System.currentTimeMillis(), free * 4,
                settings.sellerConcurrency(), breakers.openSellers());
        for (PartnerTask task : candidates) {
            if (admission.active() >= settings.workers()) break;
            if (!admission.tryAdmitLocal(task).admitted()) continue;
            String seller = task.sellerId();
            if (!breakers.tryAcquire(seller)) {
                admission.release(task);
                continue;
            }
            ClaimAttempt attempt;
            try {
                attempt = tx.execute(status -> inbox.claim(task.eventId(), seller, tracer.instance(),
                        settings.sellerConcurrency(), settings.retryBudget(), settings.retryWindowMs(), settings.ownership().leaseMs()));
            } catch (Exception e) {
                // Lock waits and deadlocks with another instance end here; the row is tried again later.
                breakers.release(seller);
                admission.release(task);
                tracer.trace("claim_error", task, "error", e.toString());
                continue;
            }
            if (attempt.result() != ClaimResult.CLAIMED) {
                breakers.release(seller);
                admission.release(task);
                if (attempt.result() == ClaimResult.RETRY_BUDGET) tracer.trace("retry_budget_wait", task);
                continue;
            }
            Claim claim = attempt.claim();
            leases.track(claim);
            tracer.trace("claimed", task, "generation", claim.generation(), "slot", claim.slot(),
                    "retry", claim.retry(), "reclaimed", claim.reclaimed());
            try {
                executor.execute(() -> runClaimed(task, claim));
            } catch (RejectedExecutionException stopping) {
                leases.untrack(claim);
                tx.executeWithoutResult(status -> inbox.giveBack(claim, System.currentTimeMillis()));
                breakers.release(seller);
                admission.release(task);
            }
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        executor.shutdown();
        try {
            boolean drained = executor.awaitTermination(settings.shutdownDrainMs(), TimeUnit.MILLISECONDS);
            tracer.trace("worker_drain", null, "drained", drained, "active", admission.active());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Listener containers stop at Integer.MAX_VALUE - 100; stopping later means intake is already off.
        return Integer.MAX_VALUE - 1000;
    }

    /** Inbox: deliver under the claim, or give the row back with a backoff while the claim still holds. */
    void runClaimed(PartnerTask task, Claim claim) {
        try {
            processor.process(task, claim, true);
            admission.markFinished(task.eventId());
            BoundaryGate.hit("worker_commit", task.eventId());
        } catch (StaleClaimException stale) {
            tracer.trace("stale_owner_rejected", task, "generation", claim.generation());
        } catch (Exception e) {
            long nextAt = System.currentTimeMillis() + backoff.delayMs(task.eventId(), (int) claim.generation());
            boolean held;
            try {
                held = Boolean.TRUE.equals(tx.execute(status -> inbox.giveBack(claim, nextAt)));
            } catch (Exception error) {
                held = false;
                tracer.trace("retry_save_error", task, "error", error.toString());
            }
            tracer.trace("retry_scheduled", task, "nextAt", nextAt, "error", e.toString(),
                    "generation", claim.generation(), "claimHeld", held);
        } finally {
            leases.untrack(claim);
            admission.release(task);
            wake();
        }
    }

    /** asyncAcks: deliver, then acknowledge if the assignment is still current. */
    void run(PartnerTask task) {
        boolean success = false;
        try {
            task.nextAttempt();
            processor.process(task);
            BoundaryGate.hit("business_commit", task.eventId());
            if (task.ack() != null && pending.isCurrent(task)) {
                task.ack().acknowledge();
                tracer.trace("ack_requested", task, "meaning", "business completed");
            }
            success = true;
        } catch (Exception e) {
            task.nextAt(System.currentTimeMillis() + settings.retryDelayMs());
            tracer.trace("retry_scheduled", task, "nextAt", task.nextAt(), "error", e.toString());
        } finally {
            // Leave the queue before releasing the order key, so a stale head is never re-admitted.
            if (success && task.ack() != null) pending.completed(task);
            admission.release(task);
            wake();
        }
    }
}
