package com.ordersystem.notification_service.service;

import com.ordersystem.common.events.NotificationRequestedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDispatchLimiterTest {

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new NotificationDispatchLimiter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesOneCapacityLimitAcrossBulkRequests() {
        NotificationDispatchLimiter limiter = new NotificationDispatchLimiter(100);
        long startedAt = System.nanoTime();

        limiter.acquire(NotificationRequestedEvent.Priority.BULK);
        limiter.acquire(NotificationRequestedEvent.Priority.BULK);
        limiter.acquire(NotificationRequestedEvent.Priority.BULK);

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(15L);
    }
}
