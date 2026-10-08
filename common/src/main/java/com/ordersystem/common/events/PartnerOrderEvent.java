package com.ordersystem.common.events;

/**
 * Sequence is assigned atomically with the source order mutation. {@code padding} only fixes the
 * serialized size in load tests; it carries no business meaning and may be null.
 */
public record PartnerOrderEvent(String eventId, String runId, String sellerId, long orderId,
                                int sequence, String operation, long occurredAt,
                                int schemaVersion, int quantity, double price, String padding) {
    public PartnerOrderEvent(String eventId, String runId, String sellerId, long orderId, int sequence,
                             String operation, long occurredAt, int schemaVersion, int quantity, double price) {
        this(eventId, runId, sellerId, orderId, sequence, operation, occurredAt, schemaVersion, quantity, price, null);
    }

    /** Business ordering key; the Kafka routing key may differ (see the source outbox). */
    public String key() { return sellerId + ":" + orderId; }

    public void validate() {
        if (eventId == null || eventId.length() > 64 || runId == null || runId.length() > 64
                || sellerId == null || !sellerId.matches("[a-zA-Z0-9_-]{1,32}")
                || orderId <= 0 || sequence <= 0 || schemaVersion != 1 || occurredAt <= 0
                || !java.util.Set.of("CREATE", "CHANGE", "CANCEL").contains(operation)
                || quantity <= 0 || !Double.isFinite(price) || price < 0
                || (padding != null && padding.length() > 4096))
            throw new IllegalArgumentException("Invalid partner event");
    }
}
