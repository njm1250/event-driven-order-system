package com.ordersystem.partner.processing;

import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.completion.CompletionOutbox;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository;
import com.ordersystem.partner.inbox.InboxRepository.Claim;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import static com.ordersystem.partner.processing.DeliveryDeferredException.Reason.*;

/**
 * Delivers one operation to the seller and records it. Shared by every processing mode, so the
 * modes differ only in when this is called, not in what counts as a correct delivery.
 */
@Component
public class PartnerOrderProcessor {
    private final PartnerOrderRepository orders;
    private final InboxRepository inbox;
    private final PartnerApiClient client;
    private final SellerCircuitBreakers breakers;
    private final CompletionOutbox completions;
    private final TransactionTemplate tx;
    private final PartnerSettings settings;
    private final Tracer tracer;

    public PartnerOrderProcessor(PartnerOrderRepository orders, InboxRepository inbox, PartnerApiClient client,
                                 SellerCircuitBreakers breakers, CompletionOutbox completions, TransactionTemplate tx,
                                 PartnerSettings settings, Tracer tracer) {
        this.orders = orders;
        this.inbox = inbox;
        this.client = client;
        this.breakers = breakers;
        this.completions = completions;
        this.tx = tx;
        this.settings = settings;
        this.tracer = tracer;
    }

    /** Returns normally once the operation is delivered and committed, including duplicates. */
    public void process(PartnerTask task) throws Exception {
        process(task, null, false);
    }

    /**
     * @param claim           inbox claim whose token must still hold when the delivery is recorded;
     *                        null outside the inbox
     * @param breakerAcquired the caller already holds the seller's circuit breaker permission
     */
    public void process(PartnerTask task, Claim claim, boolean breakerAcquired) throws Exception {
        var event = task.event();
        if (orders.isDelivered(event.eventId())) {
            if (breakerAcquired) breakers.release(event.sellerId());
            tracer.trace("duplicate_business", task);
            if (claim != null) {
                tx.executeWithoutResult(status -> {
                    if (inbox.holds(claim)) inbox.complete(claim, System.currentTimeMillis());
                    else inbox.release(claim);
                });
            }
            return;
        }
        // The partner must never see CHANGE before CREATE, so check before calling it.
        if (orders.lastSequence(event.sellerId(), event.orderId()) + 1 != event.sequence()) {
            if (breakerAcquired) breakers.release(event.sellerId());
            throw new DeliveryDeferredException(PREDECESSOR_PENDING, "Waiting for predecessor");
        }
        callPartner(task, breakerAcquired);
        BoundaryGate.hit("external_success", event.eventId());
        long begin = System.nanoTime();
        tx.executeWithoutResult(status -> {
            tracer.trace("db_acquired", task, "waitMs", (System.nanoTime() - begin) / 1e6);
            // A stalled owner whose lease ran out must not record anything, even if the call succeeded:
            // the work may already be running elsewhere under a newer claim.
            if (claim != null && !inbox.holds(claim)) {
                throw new StaleClaimException(claim);
            }
            if (orders.isDelivered(event.eventId())) {
                if (claim != null) inbox.complete(claim, System.currentTimeMillis());
                return;
            }
            if (orders.lastSequence(event.sellerId(), event.orderId()) + 1 != event.sequence()) {
                throw new IllegalStateException("Predecessor changed");
            }
            long now = System.currentTimeMillis();
            orders.recordDelivery(event, now);
            completions.record(event, now);
            if (claim != null) inbox.complete(claim, now);
            else if (settings.mode() == ProcessingMode.INBOX) inbox.markDone(event.eventId(), now, task.attempts());
            BoundaryGate.hit("business_before_commit", event.eventId());
        });
        tracer.trace("business_commit", task, "generation", claim == null ? null : claim.generation());
    }

    private void callPartner(PartnerTask task, boolean breakerAcquired) throws Exception {
        if (!settings.mode().usesCircuitBreaker()) {
            task.markHttpAttempt();
            client.submit(task);
            return;
        }
        String seller = task.sellerId();
        if (!breakerAcquired && !breakers.tryAcquire(seller)) {
            tracer.trace("circuit_open", task, "state", breakers.state(seller).name());
            throw new DeliveryDeferredException(CIRCUIT_OPEN, "Circuit open for seller " + seller);
        }
        task.markHttpAttempt();
        long start = System.nanoTime();
        try {
            breakers.onSuccess(seller, client.submit(task));
        } catch (Exception failure) {
            breakers.onError(seller, System.nanoTime() - start, failure);
            throw failure;
        }
    }

    /** The claim's lease ran out before the delivery was recorded; nothing was written. */
    public static class StaleClaimException extends IllegalStateException {
        public StaleClaimException(Claim claim) {
            super("Claim no longer held: " + claim.eventId() + " generation " + claim.generation());
        }
    }
}
