package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchService {

    private final NotificationDispatchLimiter dispatchLimiter;
    private final FakeNotificationSender notificationSender;

    public NotificationDispatchService(
            NotificationDispatchLimiter dispatchLimiter,
            FakeNotificationSender notificationSender) {
        this.dispatchLimiter = dispatchLimiter;
        this.notificationSender = notificationSender;
    }

    public void dispatch(NotificationRequestedEvent event) {
        dispatchLimiter.acquire(event.getPriority());
        notificationSender.send(event);
    }
}
