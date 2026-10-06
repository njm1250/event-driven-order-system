package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Moves events that cannot be delivered now to the retry topic, so the main partition keeps
 * flowing. Once one operation of an order is parked, later operations of the same order follow it;
 * the database sequence guard still rejects any out-of-order delivery after a restart.
 */
@Component
public class RetryLane {
    static final String ATTEMPTS = "retry-attempts";
    static final String NOT_BEFORE = "retry-not-before";

    private final KafkaTemplate<String, String> producer;
    private final ObjectMapper json;
    private final PartnerSettings settings;
    private final Tracer tracer;
    private final Map<String, Set<String>> parkedByOrder = new HashMap<>();

    public RetryLane(KafkaTemplate<String, String> producer, ObjectMapper json, PartnerSettings settings, Tracer tracer) {
        this.producer = producer;
        this.json = json;
        this.settings = settings;
        this.tracer = tracer;
    }

    public synchronized boolean hasParked(String orderKey) {
        return parkedByOrder.containsKey(orderKey);
    }

    /** Publishes synchronously; the caller acks its own record only after this returns. */
    public void park(PartnerTask task, String reason) throws Exception {
        long notBefore = System.currentTimeMillis() + settings.retryDelayMs();
        var record = new ProducerRecord<>(settings.retryTopic(), task.key(), json.writeValueAsString(task.event()));
        record.headers().add(ATTEMPTS, ByteBuffer.allocate(4).putInt(task.attempts()).array());
        record.headers().add(NOT_BEFORE, ByteBuffer.allocate(8).putLong(notBefore).array());
        producer.send(record).get(5, TimeUnit.SECONDS);
        synchronized (this) {
            parkedByOrder.computeIfAbsent(task.key(), k -> new HashSet<>()).add(task.eventId());
        }
        tracer.trace("retry_parked", task, "reason", reason, "notBefore", notBefore);
        BoundaryGate.hit("retry_published", task.eventId());
    }

    public synchronized void delivered(PartnerTask task) {
        var parked = parkedByOrder.get(task.key());
        if (parked == null) return;
        parked.remove(task.eventId());
        if (parked.isEmpty()) parkedByOrder.remove(task.key());
    }

    public synchronized int parkedOrders() {
        return parkedByOrder.size();
    }
}
