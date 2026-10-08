package com.ordersystem.partner.processing;

import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.inbox.InboxRepository;
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
    private final TransactionTemplate tx;
    private final PartnerSettings settings;
    private final Tracer tracer;

    public PartnerOrderProcessor(PartnerOrderRepository orders, InboxRepository inbox, PartnerApiClient client,
                                 SellerCircuitBreakers breakers, TransactionTemplate tx,
                                 PartnerSettings settings, Tracer tracer) {
        this.orders = orders;
        this.inbox = inbox;
        this.client = client;
        this.breakers = breakers;
        this.tx = tx;
        this.settings = settings;
        this.tracer = tracer;
    }

    /** Returns normally once the operation is delivered and committed, including duplicates. */
    public void process(PartnerTask task) throws Exception {
        var event = task.event();
        if (orders.isDelivered(event.eventId())) {
            tracer.trace("duplicate_business", task);
            return;
        }
        // The partner must never see CHANGE before CREATE, so check before calling it.
        if (orders.lastSequence(event.sellerId(), event.orderId()) + 1 != event.sequence()) {
            throw new DeliveryDeferredException(PREDECESSOR_PENDING, "Waiting for predecessor");
        }
        callPartner(task);
        BoundaryGate.hit("external_success", event.eventId());
        long begin = System.nanoTime();
        tx.executeWithoutResult(status -> {
            tracer.trace("db_acquired", task, "waitMs", (System.nanoTime() - begin) / 1e6);
            if (orders.isDelivered(event.eventId())) return;
            if (orders.lastSequence(event.sellerId(), event.orderId()) + 1 != event.sequence()) {
                throw new IllegalStateException("Predecessor changed");
            }
            long now = System.currentTimeMillis();
            orders.recordDelivery(event, now);
            if (settings.mode() == ProcessingMode.INBOX) inbox.markDone(event.eventId(), now, task.attempts());
            BoundaryGate.hit("business_before_commit", event.eventId());
        });
        tracer.trace("business_commit", task);
    }

    private void callPartner(PartnerTask task) throws Exception {
        if (!settings.mode().usesCircuitBreaker()) {
            client.submit(task);
            return;
        }
        String seller = task.sellerId();
        if (!breakers.tryAcquire(seller)) {
            tracer.trace("circuit_open", task, "state", breakers.state(seller).name());
            throw new DeliveryDeferredException(CIRCUIT_OPEN, "Circuit open for seller " + seller);
        }
        long start = System.nanoTime();
        try {
            breakers.onSuccess(seller, client.submit(task));
        } catch (Exception failure) {
            breakers.onError(seller, System.nanoTime() - start, failure);
            throw failure;
        }
    }
}
