package com.ordersystem.partner.dispatch;

import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final Tracer tracer;
    private final ThreadPoolExecutor executor;
    private final ExecutorService wakeups = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dispatch-wakeup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private volatile boolean running = true;

    public WorkerDispatcher(PartnerSettings settings, InboxRepository inbox, AsyncPendingQueue pending,
                            AdmissionPolicy admission, PartnerOrderProcessor processor, Tracer tracer) {
        this.settings = settings;
        this.inbox = inbox;
        this.pending = pending;
        this.admission = admission;
        this.processor = processor;
        this.tracer = tracer;
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
            List<PartnerTask> candidates = settings.mode() == ProcessingMode.INBOX
                    ? inbox.findReady(System.currentTimeMillis(), settings.backlogLimit())
                    : pending.heads();
            for (PartnerTask task : candidates) {
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
        } catch (Exception e) {
            tracer.trace("dispatch_error", null, "error", e.toString());
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

    void run(PartnerTask task) {
        boolean inboxMode = settings.mode() == ProcessingMode.INBOX;
        boolean success = false;
        try {
            int attempt = task.nextAttempt();
            // Saving the attempt before the call keeps it even if the JVM dies mid-call, at the cost of a
            // separate commit per event. Without it the attempt is written with the outcome.
            if (inboxMode && settings.inboxAttemptsBeforeCall()) inbox.saveAttempts(task.eventId(), attempt);
            processor.process(task);
            // Inbox only: a Kafka redelivery of the same event is absorbed by the inbox key, but in the
            // async mode it is a new record that still has to run once to be acknowledged.
            if (inboxMode) admission.markFinished(task.eventId());
            BoundaryGate.hit(inboxMode ? "worker_commit" : "business_commit", task.eventId());
            if (task.ack() != null && pending.isCurrent(task)) {
                task.ack().acknowledge();
                tracer.trace("ack_requested", task, "meaning", "business completed");
            }
            success = true;
        } catch (Exception e) {
            task.nextAt(System.currentTimeMillis() + settings.retryDelayMs());
            tracer.trace("retry_scheduled", task, "nextAt", task.nextAt(), "error", e.toString());
            if (inboxMode) {
                try {
                    inbox.scheduleRetry(task.eventId(), task.nextAt(), task.attempts());
                } catch (Exception error) {
                    tracer.trace("retry_save_error", task, "error", error.toString());
                }
            }
        } finally {
            // Leave the queue before releasing the order key, so a stale head is never re-admitted.
            if (success && task.ack() != null) pending.completed(task);
            admission.release(task);
            wake();
        }
    }
}
