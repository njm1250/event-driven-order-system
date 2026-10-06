package com.ordersystem.partner.config;

/**
 * How partner API calls are scheduled relative to Kafka offsets. Every mode keeps the same
 * ordering guard (sellerId + orderId sequence) and the same external idempotency key.
 */
public enum ProcessingMode {
    /** Listener calls the partner API and acks after the business commit. */
    SEQUENTIAL,
    /** Spring asyncAcks: workers finish out of order, but a missing ack holds the next poll. */
    ASYNC,
    /** Durable inbox: the offset commit only means "stored", workers deliver later. */
    INBOX,
    /** Sequential listener plus a per-seller circuit breaker; failed calls retry in place. */
    CIRCUIT_BREAKER,
    /** Circuit breaker plus a retry topic, so open-circuit sellers leave the main partition. */
    RETRY_TOPIC,
    /** Confluent Parallel Consumer with per-key ordering and its own offset tracking, library defaults. */
    PARALLEL_CONSUMER;

    public String label() {
        return name().toLowerCase().replace('_', '-');
    }

    public boolean usesCircuitBreaker() {
        return this == CIRCUIT_BREAKER || this == RETRY_TOPIC;
    }

    public boolean usesWorkerPool() {
        return this == ASYNC || this == INBOX;
    }
}
