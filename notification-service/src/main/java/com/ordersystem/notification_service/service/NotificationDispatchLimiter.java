package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 모든 알림 lane이 외부 발송사의 처리 한도를 함께 사용하도록 제한한다.
 * 실시간 요청이 기다리는 동안에는 대량 발송이 다음 슬롯을 먼저 예약하지 못한다.
 */
@Component
public class NotificationDispatchLimiter {

    private final long intervalNanos;
    private final AtomicLong nextSlotNanos;
    private final AtomicInteger realtimeWaiters = new AtomicInteger();

    public NotificationDispatchLimiter(
            @Value("${app.notification.dispatch-rate-per-second:10000}") long ratePerSecond) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("dispatch rate must be positive");
        }
        this.intervalNanos = Math.max(1L, 1_000_000_000L / ratePerSecond);
        this.nextSlotNanos = new AtomicLong(System.nanoTime());
    }

    public void acquire(NotificationRequestedEvent.Priority priority) {
        if (priority == NotificationRequestedEvent.Priority.REALTIME) {
            realtimeWaiters.incrementAndGet();
            try {
                waitForSlot();
            } finally {
                realtimeWaiters.decrementAndGet();
            }
            return;
        }

        while (realtimeWaiters.get() > 0) {
            LockSupport.parkNanos(50_000L);
        }
        waitForSlot();
    }

    private void waitForSlot() {
        long reserved;
        while (true) {
            long current = nextSlotNanos.get();
            long now = System.nanoTime();
            reserved = Math.max(current, now);
            if (nextSlotNanos.compareAndSet(current, reserved + intervalNanos)) {
                break;
            }
        }

        long remaining;
        while ((remaining = reserved - System.nanoTime()) > 0L) {
            LockSupport.parkNanos(remaining);
        }
    }
}
