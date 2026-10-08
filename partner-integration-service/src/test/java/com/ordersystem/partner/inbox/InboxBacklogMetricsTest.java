package com.ordersystem.partner.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.support.MySqlTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static com.ordersystem.partner.support.Fixtures.settings;
import static com.ordersystem.partner.support.Fixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

class InboxBacklogMetricsTest extends MySqlTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private final InboxRepository inbox = new InboxRepository(db, json);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InboxBacklogMetrics metrics =
            new InboxBacklogMetrics(db, settings(ProcessingMode.INBOX, "http://unused"), registry);

    @Test
    void reportsOldestUndeliveredAgeAndCountPerSeller() {
        long now = System.currentTimeMillis();
        tx.execute(s -> inbox.store(task("slow", 1, 1), "{}", 200, 2000, now - 5_000));
        tx.execute(s -> inbox.store(task("slow", 2, 1), "{}", 200, 2000, now - 1_000));
        tx.execute(s -> inbox.store(task("normal", 3, 1), "{}", 200, 2000, now - 200));
        inbox.markDone("normal-3-1", now, 1);

        metrics.refresh();

        assertThat(metrics.latest()).containsOnlyKeys("slow");
        assertThat(metrics.latest().get("slow").pending()).isEqualTo(2);
        assertThat(metrics.latest().get("slow").oldestAgeMs()).isGreaterThanOrEqualTo(5_000);
        assertThat(registry.get("partner.inbox.oldest.pending.age").tag("seller", "slow").gauge().value())
                .isGreaterThanOrEqualTo(5.0);
    }
}
