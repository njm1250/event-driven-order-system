package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * Slow lane for parked events. It may block, because only sellers that already failed are here.
 * A failed retry is appended again instead of blocking the lane on one event.
 */
@Component
public class RetryTopicListener {
    private final ObjectMapper json;
    private final PartnerOrderProcessor processor;
    private final RetryLane retryLane;
    private final Tracer tracer;

    public RetryTopicListener(ObjectMapper json, PartnerOrderProcessor processor, RetryLane retryLane, Tracer tracer) {
        this.json = json;
        this.processor = processor;
        this.retryLane = retryLane;
        this.tracer = tracer;
    }

    @KafkaListener(id = "partner-retry", groupId = "${spring.kafka.consumer.group-id}-retry",
            topics = "${app.topic}-retry", containerFactory = "retryFactory")
    public void receive(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        PartnerOrderEvent event = json.readValue(record.value(), PartnerOrderEvent.class);
        var task = new PartnerTask(event, ack, record.topic(), record.partition(), record.offset());
        task.attempts(readInt(record.headers().lastHeader(RetryLane.ATTEMPTS)));
        long wait = readLong(record.headers().lastHeader(RetryLane.NOT_BEFORE)) - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        tracer.trace("retry_received", task);
        task.nextAttempt();
        try {
            processor.process(task);
            retryLane.delivered(task);
            BoundaryGate.hit("business_commit", task.eventId());
        } catch (Exception e) {
            retryLane.park(task, e.toString());
        }
        ack.acknowledge();
        tracer.trace("ack_requested", task, "meaning", "retry delivered or parked again");
    }

    private static int readInt(Header header) {
        return header == null ? 0 : ByteBuffer.wrap(header.value()).getInt();
    }

    private static long readLong(Header header) {
        return header == null ? 0 : ByteBuffer.wrap(header.value()).getLong();
    }
}
