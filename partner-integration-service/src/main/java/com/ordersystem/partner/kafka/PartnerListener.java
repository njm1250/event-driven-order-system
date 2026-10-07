package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.dispatch.AsyncPendingQueue;
import com.ordersystem.partner.dispatch.WorkerDispatcher;
import com.ordersystem.partner.inbox.InboxRepository;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Receives partner order events and applies the configured processing mode. */
@Component
public class PartnerListener {
    private final PartnerSettings settings;
    private final ObjectMapper json;
    private final PartnerOrderProcessor processor;
    private final InboxRepository inbox;
    private final AsyncPendingQueue pending;
    private final RetryLane retryLane;
    private final TransactionTemplate tx;
    private final WorkerDispatcher dispatcher;
    private final Tracer tracer;
    private final Map<Integer, Long> receivedOffsets = new ConcurrentHashMap<>();

    public PartnerListener(PartnerSettings settings, ObjectMapper json, PartnerOrderProcessor processor,
                           InboxRepository inbox, AsyncPendingQueue pending, RetryLane retryLane,
                           TransactionTemplate tx, WorkerDispatcher dispatcher, Tracer tracer) {
        this.settings = settings;
        this.json = json;
        this.processor = processor;
        this.inbox = inbox;
        this.pending = pending;
        this.retryLane = retryLane;
        this.tx = tx;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
    }

    public Map<Integer, Long> receivedOffsets() {
        return receivedOffsets;
    }

    @KafkaListener(id = "partner", groupId = "${spring.kafka.consumer.group-id}", topics = "${app.topic}",
            containerFactory = "partnerFactory")
    public void receive(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        PartnerOrderEvent event = json.readValue(record.value(), PartnerOrderEvent.class);
        event.validate();
        var task = new PartnerTask(event, ack, record.topic(), record.partition(), record.offset());
        receivedOffsets.merge(record.partition(), record.offset() + 1, Math::max);
        tracer.trace("received", task);
        switch (settings.mode()) {
            case INBOX -> storeInInbox(task, record.value());
            case SEQUENTIAL, CIRCUIT_BREAKER -> deliverInPlace(task);
            case ASYNC -> {
                pending.add(task);
                dispatcher.wake();
            }
            case RETRY_TOPIC -> deliverOrPark(task);
            case PARALLEL_CONSUMER -> throw new IllegalStateException("Parallel Consumer does not use this listener");
        }
    }

    /** The offset commit only means "stored"; delivery happens later in WorkerDispatcher. */
    private void storeInInbox(PartnerTask task, String payload) {
        long begin = System.nanoTime();
        tx.executeWithoutResult(status -> {
            tracer.trace("db_acquired", task, "waitMs", (System.nanoTime() - begin) / 1e6);
            try {
                inbox.store(task, payload, settings.backlogLimit(), settings.retainedLimit(), System.currentTimeMillis());
            } catch (InboxRepository.InboxFullException full) {
                tracer.trace("backpressure", task);
                throw full;
            }
            BoundaryGate.hit("inbox_before_commit", task.eventId());
        });
        tracer.trace("inbox_commit", task);
        BoundaryGate.hit("inbox_commit", task.eventId());
        task.ack().acknowledge();
        tracer.trace("ack_requested", task, "meaning", "inbox handoff");
        dispatcher.wake();
    }

    /** Blocks this partition until the operation is delivered, however long that takes. */
    private void deliverInPlace(PartnerTask task) throws InterruptedException {
        while (true) {
            task.nextAttempt();
            try {
                processor.process(task);
                break;
            } catch (Exception e) {
                tracer.trace("retry_scheduled", task, "error", e.toString());
                Thread.sleep(settings.retryDelayMs());
            }
        }
        BoundaryGate.hit("business_commit", task.eventId());
        task.ack().acknowledge();
        tracer.trace("ack_requested", task, "meaning", "business completed");
    }

    /** One try on the main topic; anything that cannot finish now moves to the retry topic. */
    private void deliverOrPark(PartnerTask task) throws Exception {
        if (retryLane.hasParked(task.key())) {
            retryLane.park(task, "order already parked");
        } else {
            task.nextAttempt();
            try {
                processor.process(task);
                BoundaryGate.hit("business_commit", task.eventId());
            } catch (Exception e) {
                retryLane.park(task, e.toString());
            }
        }
        task.ack().acknowledge();
        tracer.trace("ack_requested", task, "meaning", "delivered or parked");
    }
}
