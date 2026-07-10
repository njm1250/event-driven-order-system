package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.OrderStockUpdateFailedEvent;
import com.ordersystem.common.events.OrderStockUpdatedEvent;
import com.ordersystem.common.events.Topics;
import com.ordersystem.notification_service.entity.NotificationHistory;
import com.ordersystem.notification_service.repository.NotificationHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 확정/취소 이벤트를 order-group과 별개의 컨슈머 그룹으로 구독해
 * 사용자 알림을 발송한다. 같은 토픽을 그룹별로 각자 소비하는 브로드캐스트 구조.
 */
@Service
@Slf4j
public class NotificationConsumer {

    private final NotificationHistoryRepository notificationHistoryRepository;
    private final FakeNotificationSender notificationSender;
    private final boolean idempotencyEnabled;

    public NotificationConsumer(NotificationHistoryRepository notificationHistoryRepository,
                                FakeNotificationSender notificationSender,
                                @Value("${app.idempotency-enabled:true}") boolean idempotencyEnabled) {
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.notificationSender = notificationSender;
        this.idempotencyEnabled = idempotencyEnabled;
    }

    @KafkaListener(topics = Topics.STOCK_UPDATED, groupId = "notification-group")
    @Transactional
    public void onOrderConfirmed(OrderStockUpdatedEvent event) {
        notify(event.getEventId(), event.getOrderId(), NotificationHistory.Type.ORDER_CONFIRMED, null);
    }

    @KafkaListener(topics = Topics.STOCK_UPDATE_FAILED, groupId = "notification-group")
    @Transactional
    public void onOrderCancelled(OrderStockUpdateFailedEvent event) {
        notify(event.getEventId(), event.getOrderId(), NotificationHistory.Type.ORDER_CANCELLED, event.getReason());
    }

    private void notify(String eventId, Long orderId, NotificationHistory.Type type, String reason) {
        // at-least-once 재전달이 같은 알림을 두 번 보내지 않도록 발송 이력으로 판별한다.
        if (idempotencyEnabled && notificationHistoryRepository.existsByEventId(eventId)) {
            log.info("Duplicate event {} for order {}; notification already sent, skipping", eventId, orderId);
            return;
        }

        notificationSender.send(orderId, type, reason);
        notificationHistoryRepository.save(NotificationHistory.builder()
                .eventId(eventId)
                .orderId(orderId)
                .type(type)
                .build());
    }
}
