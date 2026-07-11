package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import com.ordersystem.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 대량 발송 backlog가 실시간 알림의 Kafka 소비 순서를 막지 않도록 토픽과
 * consumer group을 분리한다. 두 lane의 실제 발송 용량은 dispatch service가 공유한다.
 */
@Component
public class NotificationRequestConsumer {

    private final NotificationDispatchService dispatchService;

    public NotificationRequestConsumer(NotificationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @KafkaListener(
            topics = Topics.NOTIFICATION_REALTIME,
            groupId = "notification-realtime-group",
            concurrency = "${app.notification.realtime-concurrency:2}")
    public void consumeRealtime(NotificationRequestedEvent event) {
        dispatchService.dispatch(event);
    }

    @KafkaListener(
            topics = Topics.NOTIFICATION_BULK,
            groupId = "notification-bulk-group",
            concurrency = "${app.notification.bulk-concurrency:10}")
    public void consumeBulk(NotificationRequestedEvent event) {
        dispatchService.dispatch(event);
    }
}
