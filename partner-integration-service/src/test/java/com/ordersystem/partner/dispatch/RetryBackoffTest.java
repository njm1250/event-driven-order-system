package com.ordersystem.partner.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTest {
    private final RetryBackoff backoff = new RetryBackoff(300, 5000, 1251);

    @Test
    void sameEventAndAttemptAlwaysWaitTheSame() {
        assertThat(new RetryBackoff(300, 5000, 1251).delayMs("e-1", 3)).isEqualTo(backoff.delayMs("e-1", 3));
    }

    @Test
    void waitStaysWithinTheGrowingCeiling() {
        for (int failures = 1; failures <= 10; failures++) {
            long ceiling = Math.min(5000, 300L << (failures - 1));
            for (int event = 0; event < 50; event++) {
                assertThat(backoff.delayMs("e-" + event, failures)).isBetween(0L, ceiling);
            }
        }
    }

    @Test
    void differentSeedsGiveDifferentWaits() {
        var other = new RetryBackoff(300, 5000, 1252);
        long differing = java.util.stream.IntStream.range(0, 20)
                .filter(i -> other.delayMs("e-" + i, 4) != backoff.delayMs("e-" + i, 4)).count();
        assertThat(differing).isGreaterThan(10);
    }
}
