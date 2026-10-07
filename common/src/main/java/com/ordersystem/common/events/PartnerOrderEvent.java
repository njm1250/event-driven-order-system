package com.ordersystem.common.events;

/** Sequence is assigned atomically with the source order mutation. */
public record PartnerOrderEvent(String eventId, String runId, String sellerId, long orderId,
                                int sequence, String operation, long occurredAt,
                                int schemaVersion, int quantity, double price) {
    public String key() { return sellerId + ":" + orderId; }
    public void validate() {
        if (eventId == null || eventId.length() > 64 || runId == null || runId.length() > 64
                || sellerId == null || !sellerId.matches("[a-zA-Z0-9_-]{1,32}")
                || orderId <= 0 || sequence <= 0 || schemaVersion != 1 || occurredAt <= 0
                || !java.util.Set.of("CREATE", "CHANGE", "CANCEL").contains(operation)
                || quantity <= 0 || !Double.isFinite(price) || price < 0)
            throw new IllegalArgumentException("Invalid partner event");
    }
}
