package com.ordersystem.partner;

import com.ordersystem.common.events.PartnerOrderEvent;
import org.springframework.kafka.support.Acknowledgment;

/** One delivery attempt context: the event plus where it came from and how often it was tried. */
public final class PartnerTask {
    private final PartnerOrderEvent event;
    private final Acknowledgment ack;
    private final String topic;
    private final int partition;
    private final long offset;
    private int attempts;
    private int httpAttempts;
    private int failures;
    private long nextAt;
    private long epoch;

    public PartnerTask(PartnerOrderEvent event, Acknowledgment ack, String topic, int partition, long offset) {
        this.event = event;
        this.ack = ack;
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
    }

    public PartnerOrderEvent event() { return event; }
    public Acknowledgment ack() { return ack; }
    public String topic() { return topic; }
    public int partition() { return partition; }
    public long offset() { return offset; }
    public String key() { return event.key(); }
    public String eventId() { return event.eventId(); }
    public String sellerId() { return event.sellerId(); }

    public int attempts() { return attempts; }
    public void attempts(int value) { attempts = value; }
    public int nextAttempt() { return ++attempts; }

    /** Partner calls actually started for this event, as opposed to deliveries tried. */
    public int httpAttempts() { return httpAttempts; }
    public void httpAttempts(int value) { httpAttempts = value; }
    public void markHttpAttempt() { httpAttempts++; }

    /** How often the event was put back (failed call, open circuit, limit reached); drives the backoff. */
    public int failures() { return failures; }
    public void failures(int value) { failures = value; }
    public int nextFailure() { return ++failures; }

    public long nextAt() { return nextAt; }
    public void nextAt(long value) { nextAt = value; }

    public long epoch() { return epoch; }
    public void epoch(long value) { epoch = value; }
}
