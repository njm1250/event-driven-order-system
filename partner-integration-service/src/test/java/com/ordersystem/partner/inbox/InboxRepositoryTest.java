package com.ordersystem.partner.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.support.MySqlTestBase;
import org.junit.jupiter.api.Test;

import static com.ordersystem.partner.support.Fixtures.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxRepositoryTest extends MySqlTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private final InboxRepository inbox = new InboxRepository(db, json);

    private boolean store(PartnerTask task, int pendingLimit) throws Exception {
        String payload = json.writeValueAsString(task.event());
        return tx.execute(s -> inbox.store(task, payload, pendingLimit, 2000, System.currentTimeMillis()));
    }

    @Test
    void kafkaRedeliveryIsStoredOnce() throws Exception {
        assertThat(store(task("normal", 1, 1), 200)).isTrue();
        assertThat(store(task("normal", 1, 1), 200)).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM inbox", Long.class)).isEqualTo(1);
    }

    @Test
    void rejectsNewEventsWhenPendingLimitIsReached() throws Exception {
        store(task("slow", 1, 1), 2);
        store(task("slow", 2, 1), 2);
        assertThatThrownBy(() -> store(task("normal", 3, 1), 2)).isInstanceOf(InboxRepository.InboxFullException.class);
    }

    @Test
    void readyRowsAreOnlyTheNextOperationOfEachOrder() throws Exception {
        store(task("normal", 1, 1), 200);
        store(task("normal", 1, 2), 200);
        store(task("normal", 2, 1), 200);

        var ready = inbox.findReady(System.currentTimeMillis(), 10);

        assertThat(ready).extracting(PartnerTask::eventId).containsExactly("normal-1-1", "normal-2-1");
    }

    @Test
    void scheduledRetryIsHiddenUntilItsTime() throws Exception {
        store(task("slow", 1, 1), 200);
        long now = System.currentTimeMillis();
        inbox.scheduleRetry("slow-1-1", now + 300);

        assertThat(inbox.findReady(now, 10)).isEmpty();
        assertThat(inbox.findReady(now + 300, 10)).hasSize(1);
    }

    @Test
    void purgeDeletesOnlyCompletedRowsOlderThanTheCutoff() throws Exception {
        store(task("normal", 1, 1), 200);
        store(task("normal", 2, 1), 200);
        store(task("normal", 3, 1), 200);
        inbox.markDone("normal-1-1", 1_000);
        inbox.markDone("normal-2-1", 5_000);

        assertThat(inbox.deleteDoneBefore(2_000, 100)).isEqualTo(1);
        assertThat(db.queryForList("SELECT event_id FROM inbox ORDER BY event_id", String.class))
                .containsExactly("normal-2-1", "normal-3-1");
    }
}
