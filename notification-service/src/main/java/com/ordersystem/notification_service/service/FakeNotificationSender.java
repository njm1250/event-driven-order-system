package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import com.ordersystem.notification_service.entity.NotificationHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 외부 푸시 게이트웨이(FCM 등) 호출을 흉내 내는 자리. 실제 발송이 아니라
 * 발송 시뮬레이션이며, 처리 지연과 실패율을 설정으로 재현한다.
 */
@Component
@Slf4j
public class FakeNotificationSender {

    private final long latencyMs;
    private final double failureRate;

    public FakeNotificationSender(@Value("${app.sender.latency-ms:50}") long latencyMs,
                                  @Value("${app.sender.failure-rate:0}") double failureRate) {
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
    }

    public void send(Long orderId, NotificationHistory.Type type, String reason) {
        simulateLatencyAndFailure("order " + orderId);
        log.info("[NOTIFY] order={} type={}{}", orderId, type, reason == null ? "" : " reason=" + reason);
    }

    public void send(NotificationRequestedEvent event) {
        simulateLatencyAndFailure("event " + event.getEventId());
        log.info(
                "[NOTIFY] event={} user={} priority={}",
                event.getEventId(),
                event.getUserId(),
                event.getPriority());
    }

    private void simulateLatencyAndFailure(String target) {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Send interrupted", e);
            }
        }

        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new IllegalStateException("Simulated send failure for " + target);
        }
    }
}
