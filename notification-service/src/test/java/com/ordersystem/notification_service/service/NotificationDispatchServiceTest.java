package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class NotificationDispatchServiceTest {

    @Test
    void acquiresSharedCapacityBeforeSending() {
        NotificationDispatchLimiter limiter = mock(NotificationDispatchLimiter.class);
        FakeNotificationSender sender = mock(FakeNotificationSender.class);
        NotificationDispatchService service = new NotificationDispatchService(limiter, sender);
        NotificationRequestedEvent event = new NotificationRequestedEvent(
                "event-1",
                "user-1",
                NotificationRequestedEvent.Priority.REALTIME,
                1L);

        service.dispatch(event);

        InOrder order = inOrder(limiter, sender);
        order.verify(limiter).acquire(NotificationRequestedEvent.Priority.REALTIME);
        order.verify(sender).send(event);
    }
}
