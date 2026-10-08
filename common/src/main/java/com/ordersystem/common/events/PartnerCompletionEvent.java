package com.ordersystem.common.events;

/**
 * Sent by the partner integration once a delivery is recorded, so the source can close the
 * delivery obligation it opened. Redelivery is expected; the source applies it once per eventId.
 */
public record PartnerCompletionEvent(String eventId, String sellerId, long orderId, int sequence, long completedAt) {
}
