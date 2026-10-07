package com.ordersystem.partner.dispatch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static com.ordersystem.partner.support.Fixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

class AdmissionPolicyTest {
    private final AtomicLong clock = new AtomicLong(10_000);
    private final AdmissionPolicy policy = new AdmissionPolicy(4, 2, 2, 1000, 100, clock::get);

    @Test
    void oneSellerCannotTakeMoreThanItsShareOfWorkers() {
        assertThat(policy.tryAdmit(task("slow", 1, 1)).admitted()).isTrue();
        assertThat(policy.tryAdmit(task("slow", 2, 1)).admitted()).isTrue();
        assertThat(policy.tryAdmit(task("slow", 3, 1)).admitted()).isFalse();
        assertThat(policy.tryAdmit(task("normal", 4, 1)).admitted()).isTrue();
    }

    @Test
    void twoOperationsOfTheSameOrderNeverRunTogether() {
        var create = task("normal", 1, 1);
        assertThat(policy.tryAdmit(create).admitted()).isTrue();
        assertThat(policy.tryAdmit(task("normal", 1, 2)).admitted()).isFalse();
        policy.release(create);
        assertThat(policy.tryAdmit(task("normal", 1, 2)).admitted()).isTrue();
    }

    @Test
    void retriesOfOneSellerAreLimitedPerWindow() {
        for (int order = 1; order <= 3; order++) {
            var retry = task("slow", order, 1);
            retry.attempts(1);
            var decision = policy.tryAdmit(retry);
            assertThat(decision.admitted()).isEqualTo(order <= 2);
            if (decision.admitted()) policy.release(retry);
        }
        clock.addAndGet(1000);
        var later = task("slow", 3, 1);
        later.attempts(1);
        assertThat(policy.tryAdmit(later)).satisfies(d -> {
            assertThat(d.admitted()).isTrue();
            assertThat(d.retry()).isTrue();
        });
    }

    @Test
    void waitsUntilTheRetryTime() {
        var task = task("slow", 1, 1);
        task.nextAt(clock.get() + 300);
        assertThat(policy.tryAdmit(task).admitted()).isFalse();
        clock.addAndGet(300);
        assertThat(policy.tryAdmit(task).admitted()).isTrue();
    }

    /** Regression: a stale ready-row snapshot was re-admitted after its worker committed (decision 005). */
    @Test
    void finishedTaskFromAStaleSnapshotIsNotAdmittedAgain() {
        var running = task("normal", 1, 1);
        var staleCopy = task("normal", 1, 1);
        assertThat(policy.tryAdmit(running).admitted()).isTrue();
        policy.markFinished(running.eventId());
        policy.release(running);

        staleCopy.attempts(1);
        assertThat(policy.tryAdmit(staleCopy).admitted()).isFalse();
        // The rejected stale copy must not have spent the seller's retry budget.
        var realRetry = task("normal", 2, 1);
        realRetry.attempts(1);
        assertThat(policy.tryAdmit(realRetry).admitted()).isTrue();
    }

    @Test
    void releaseFreesTheWorkerAndSellerSlot() {
        var first = task("slow", 1, 1);
        policy.tryAdmit(first);
        policy.tryAdmit(task("slow", 2, 1));
        policy.release(first);
        assertThat(policy.active()).isEqualTo(1);
        assertThat(policy.tryAdmit(task("slow", 3, 1)).admitted()).isTrue();
    }
}
