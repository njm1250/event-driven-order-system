package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.dispatch.RetryBackoff;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
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
    static final String HTTP_ATTEMPTS = "retry-http-attempts";
    static final String FAILURES = "retry-failures";
    static final String NOT_BEFORE = "retry-not-before";

    private final KafkaTemplate<String, String> producer;
    private final ObjectMapper json;
    private final PartnerSettings settings;
    private final Tracer tracer;
    private final RetryBackoff backoff;
    private final Map<String, Set<String>> parkedByOrder = new HashMap<>();

    public RetryLane(KafkaTemplate<String, String> producer, ObjectMapper json, PartnerSettings settings, Tracer tracer) {
        this.producer = producer;
        this.json = json;
        this.settings = settings;
        this.tracer = tracer;
        this.backoff = RetryBackoff.of(settings);
    }

    public synchronized boolean hasParked(String orderKey) {
        return parkedByOrder.containsKey(orderKey);
    }

    /** Publishes synchronously; the caller acks its own record only after this returns. */
    public void park(PartnerTask task, String reason) throws Exception {
        int failures = task.nextFailure();
        long delay = settings.mode().usesBackoff() ? backoff.delayMs(task.eventId(), failures) : settings.retryDelayMs();
        long notBefore = System.currentTimeMillis() + delay;
        var record = new ProducerRecord<>(settings.retryTopic(), task.key(), json.writeValueAsString(task.event()));
        record.headers().add(ATTEMPTS, ByteBuffer.allocate(4).putInt(task.attempts()).array());
        record.headers().add(HTTP_ATTEMPTS, ByteBuffer.allocate(4).putInt(task.httpAttempts()).array());
        record.headers().add(FAILURES, ByteBuffer.allocate(4).putInt(failures).array());
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

    /** Restores the counters a parked record carries. */
    static void restore(PartnerTask task, Headers headers) {
        task.attempts(readInt(headers.lastHeader(ATTEMPTS)));
        task.httpAttempts(readInt(headers.lastHeader(HTTP_ATTEMPTS)));
        task.failures(readInt(headers.lastHeader(FAILURES)));
    }

    static long notBefore(Headers headers) {
        Header header = headers.lastHeader(NOT_BEFORE);
        return header == null ? 0 : ByteBuffer.wrap(header.value()).getLong();
    }

    private static int readInt(Header header) {
        return header == null ? 0 : ByteBuffer.wrap(header.value()).getInt();
    }
}
