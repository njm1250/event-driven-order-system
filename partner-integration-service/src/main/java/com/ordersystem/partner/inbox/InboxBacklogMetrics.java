package com.ordersystem.partner.inbox;

import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-seller backlog signal for the inbox. Once records are stored the Kafka offset moves on, so
 * consumer lag stays near zero while a slow seller's events wait; the age of the oldest undelivered
 * event per seller shows that wait instead.
 */
@Component
public class InboxBacklogMetrics {
    public record SellerBacklog(long oldestAgeMs, long pending) {
    }

    private final JdbcTemplate db;
    private final PartnerSettings settings;
    private final MultiGauge oldestAge;
    private final MultiGauge pendingCount;
    private volatile Map<String, SellerBacklog> latest = Collections.emptyMap();

    public InboxBacklogMetrics(JdbcTemplate db, PartnerSettings settings, MeterRegistry registry) {
        this.db = db;
        this.settings = settings;
        this.oldestAge = MultiGauge.builder("partner.inbox.oldest.pending.age")
                .description("Age of the oldest undelivered inbox event per seller").baseUnit("seconds").register(registry);
        this.pendingCount = MultiGauge.builder("partner.inbox.pending")
                .description("Undelivered inbox events per seller").register(registry);
    }

    public Map<String, SellerBacklog> latest() {
        return latest;
    }

    @Scheduled(fixedDelay = 1000)
    public void refresh() {
        if (settings.mode() != ProcessingMode.INBOX) return;
        long now = System.currentTimeMillis();
        Map<String, SellerBacklog> snapshot = new LinkedHashMap<>();
        db.query("SELECT seller_id, MIN(received_at) AS oldest, COUNT(*) AS pending FROM inbox "
                        + "WHERE state<>'DONE' GROUP BY seller_id",
                row -> {
                    snapshot.put(row.getString("seller_id"),
                            new SellerBacklog(now - row.getLong("oldest"), row.getLong("pending")));
                });
        List<MultiGauge.Row<?>> ages = new ArrayList<>();
        List<MultiGauge.Row<?>> counts = new ArrayList<>();
        snapshot.forEach((seller, backlog) -> {
            ages.add(MultiGauge.Row.of(Tags.of("seller", seller), backlog.oldestAgeMs() / 1000.0));
            counts.add(MultiGauge.Row.of(Tags.of("seller", seller), backlog.pending()));
        });
        // overwrite=true drops sellers whose backlog emptied.
        oldestAge.register(ages, true);
        pendingCount.register(counts, true);
        latest = snapshot;
    }
}
