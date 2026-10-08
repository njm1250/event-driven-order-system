package com.ordersystem.partner.dispatch;

import com.ordersystem.partner.PartnerTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ordersystem.partner.support.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncPendingQueueTest {
    private final AsyncPendingQueue queue = new AsyncPendingQueue();

    private PartnerTask received(long orderId, int partition) {
        var task = new PartnerTask(event("s01", orderId, 1), null, "partner-test", partition, orderId);
        queue.add(task);
        return task;
    }

    /** Regression: revoking one container's partitions dropped every container's pending work. */
    @Test
    void revokeDropsOnlyTheRevokedPartitions() {
        var kept = received(1, 0);
        var revoked = received(2, 1);

        queue.revoke(List.of(1));

        assertThat(queue.heads()).containsExactly(kept);
        assertThat(queue.isCurrent(kept)).isTrue();
        assertThat(queue.isCurrent(revoked)).isFalse();
    }
}
