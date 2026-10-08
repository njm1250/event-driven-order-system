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
    /**
     * Durable inbox: the offset commit only means "stored". Workers on any instance claim rows with a
     * renewed lease and a per-seller permit in the database, with circuit breaker and backoff.
     */
    INBOX,
    /** Sequential listener plus a per-seller circuit breaker; failed calls retry in place. */
    CIRCUIT_BREAKER,
    /** Circuit breaker plus a retry topic, so open-circuit sellers leave the main partition. */
    RETRY_TOPIC,
    /** Confluent Parallel Consumer with per-key ordering and its own offset tracking, library defaults. */
    PARALLEL_CONSUMER,
    /**
     * Parallel Consumer, KEY ordering, where the producer routes each seller to a fixed number of
     * key buckets: the bucket count is the seller's concurrency. Circuit breaker, backoff and a
     * per-seller retry budget as in the other candidates.
     */
    KAFKA_BUCKET,
    /**
     * Retry topic with one shared admission for the main and retry listeners: the global and
     * per-seller limits hold across both, and a seller at its limit is parked instead of waited for.
     */
    KAFKA_RETRY;

    public String label() {
        return name().toLowerCase().replace('_', '-');
    }

    public boolean usesCircuitBreaker() {
        return this == CIRCUIT_BREAKER || this == RETRY_TOPIC || this == INBOX || this == KAFKA_BUCKET || this == KAFKA_RETRY;
    }

    public boolean usesWorkerPool() {
        return this == ASYNC || this == INBOX;
    }

    public boolean usesParallelConsumer() {
        return this == PARALLEL_CONSUMER || this == KAFKA_BUCKET;
    }

    public boolean usesRetryTopic() {
        return this == RETRY_TOPIC || this == KAFKA_RETRY;
    }

    /** Backoff with jitter instead of the fixed retry delay of the earlier modes. */
    public boolean usesBackoff() {
        return this == INBOX || this == KAFKA_BUCKET || this == KAFKA_RETRY;
    }
}
