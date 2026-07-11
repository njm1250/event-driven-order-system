package com.ordersystem.common.events;

/**
 * 서비스가 알림 플랫폼에 전달하는 발송 요청이다. userId를 Kafka key로 사용하면
 * 같은 사용자의 알림 순서는 유지하면서 사용자 간 처리는 병렬화할 수 있다.
 */
public class NotificationRequestedEvent {

    public enum Priority {
        REALTIME,
        BULK
    }

    private String eventId;
    private String userId;
    private Priority priority;
    private long requestedAtEpochMillis;

    public NotificationRequestedEvent() {
    }

    public NotificationRequestedEvent(
            String eventId,
            String userId,
            Priority priority,
            long requestedAtEpochMillis) {
        this.eventId = eventId;
        this.userId = userId;
        this.priority = priority;
        this.requestedAtEpochMillis = requestedAtEpochMillis;
    }

    public String getEventId() {
        return eventId;
    }

    public String getUserId() {
        return userId;
    }

    public Priority getPriority() {
        return priority;
    }

    public long getRequestedAtEpochMillis() {
        return requestedAtEpochMillis;
    }
}
