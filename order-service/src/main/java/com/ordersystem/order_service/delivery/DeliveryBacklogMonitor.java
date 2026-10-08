package com.ordersystem.order_service.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Per-seller age of the oldest accepted operation not yet delivered, and how many are past the
 * delivery target. The snapshot carries its own time, so a reader can tell a stale value (this
 * refresh stopped) from a healthy one instead of trusting the last number it saw.
 */
@ConditionalOnProperty(name = "app.delivery-tracking", havingValue = "true")
@Component
public class DeliveryBacklogMonitor {
    public record Snapshot(long takenAt, Map<String, DeliveryObligations.SellerBacklog> sellers) {
    }

    private final DeliveryObligations obligations;
    private final long targetMs;
    private final MultiGauge oldestAge;
    private final MultiGauge overdue;
    private volatile Snapshot latest = new Snapshot(0, Collections.emptyMap());
    private volatile long pausedUntil;

    public DeliveryBacklogMonitor(DeliveryObligations obligations, MeterRegistry registry,
                                  @Value("${app.delivery-target-ms:1500}") long targetMs) {
        this.obligations = obligations;
        this.targetMs = targetMs;
        this.oldestAge = MultiGauge.builder("delivery.oldest.unresolved.age").baseUnit("seconds")
                .description("Age of the oldest accepted operation not yet delivered, per seller").register(registry);
        this.overdue = MultiGauge.builder("delivery.overdue")
                .description("Accepted operations past the delivery target, per seller").register(registry);
        Gauge.builder("delivery.snapshot.age", this, m -> (System.currentTimeMillis() - m.latest.takenAt()) / 1000.0)
                .baseUnit("seconds").description("Time since the backlog was last read").register(registry);
    }

    public Snapshot latest() {
        return latest;
    }

    public long targetMs() {
        return targetMs;
    }

    /** Stops refreshing for a while, to test that readers notice a stale signal. */
    public void pause(long durationMs) {
        pausedUntil = System.currentTimeMillis() + durationMs;
    }

    @Scheduled(fixedDelay = 1000)
    public void refresh() {
        long now = System.currentTimeMillis();
        if (now < pausedUntil) return;
        var sellers = obligations.backlog(now, targetMs);
        List<MultiGauge.Row<?>> ages = new ArrayList<>();
        List<MultiGauge.Row<?>> late = new ArrayList<>();
        sellers.forEach((seller, backlog) -> {
            ages.add(MultiGauge.Row.of(Tags.of("seller", seller), (now - backlog.oldestCreatedAt()) / 1000.0));
            late.add(MultiGauge.Row.of(Tags.of("seller", seller), backlog.overdue()));
        });
        oldestAge.register(ages, true);
        overdue.register(late, true);
        latest = new Snapshot(now, sellers);
    }
}
