package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Slow lane for parked events. It may block, because only sellers that already failed are here.
 * A failed retry is appended again instead of blocking the lane on one event. In the comparison
 * candidate it also goes through the shared admission, so retries count against the same global,
 * per-seller and retry limits as first attempts.
 */
@Component
public class RetryTopicListener {
    private final ObjectMapper json;
    private final PartnerOrderProcessor processor;
    private final RetryLane retryLane;
    private final RetryAdmission admission;
    private final PartnerSettings settings;
    private final Tracer tracer;

    public RetryTopicListener(ObjectMapper json, PartnerOrderProcessor processor, RetryLane retryLane,
                              RetryAdmission admission, PartnerSettings settings, Tracer tracer) {
        this.json = json;
        this.processor = processor;
        this.retryLane = retryLane;
        this.admission = admission;
        this.settings = settings;
        this.tracer = tracer;
    }

    @KafkaListener(id = "partner-retry", groupId = "${spring.kafka.consumer.group-id}-retry",
            topics = "${app.topic}-retry", containerFactory = "retryFactory")
    public void receive(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        PartnerOrderEvent event = json.readValue(record.value(), PartnerOrderEvent.class);
        var task = new PartnerTask(event, ack, record.topic(), record.partition(), record.offset());
        RetryLane.restore(task, record.headers());
        long wait = RetryLane.notBefore(record.headers()) - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        tracer.trace("retry_received", task);
        if (settings.mode() == ProcessingMode.KAFKA_RETRY) {
            // A retry is an admission after a partner call may have started for this event.
            task.attempts(task.httpAttempts());
            if (admission.admitOrPark(task)) {
                try {
                    deliverOrParkAgain(task);
                } finally {
                    admission.release(task);
                }
            }
        } else {
            task.nextAttempt();
            deliverOrParkAgain(task);
        }
        ack.acknowledge();
        tracer.trace("ack_requested", task, "meaning", "retry delivered or parked again");
    }

    private void deliverOrParkAgain(PartnerTask task) throws Exception {
        try {
            processor.process(task);
            retryLane.delivered(task);
            BoundaryGate.hit("business_commit", task.eventId());
        } catch (Exception e) {
            retryLane.park(task, e.toString());
        }
    }
}
