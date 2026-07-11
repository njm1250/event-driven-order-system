package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationRequestConsumerTest {

    private final NotificationDispatchService dispatchService = mock(NotificationDispatchService.class);
    private final NotificationRequestConsumer consumer = new NotificationRequestConsumer(dispatchService);

    @Test
    void delegatesRealtimeLaneToSharedDispatchService() {
        NotificationRequestedEvent event = event(NotificationRequestedEvent.Priority.REALTIME);

        consumer.consumeRealtime(event);

        verify(dispatchService).dispatch(event);
    }

    @Test
    void delegatesBulkLaneToSharedDispatchService() {
        NotificationRequestedEvent event = event(NotificationRequestedEvent.Priority.BULK);

        consumer.consumeBulk(event);

        verify(dispatchService).dispatch(event);
    }

    private NotificationRequestedEvent event(NotificationRequestedEvent.Priority priority) {
        return new NotificationRequestedEvent("event-1", "user-1", priority, 1L);
    }
}
