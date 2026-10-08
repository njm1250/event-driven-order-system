package com.ordersystem.partner.completion;

import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes unsent completion reports. Each instance relays the reports it wrote; reports older
 * than {@link #ORPHAN_AFTER_MS} are relayed by any instance, so a dead instance's reports still
 * arrive. A report may be sent twice; the source applies it once per eventId.
 *
 * No transaction or row lock is held while waiting for Kafka: a process that stalls in the middle
 * (a long GC, a stopped VM) must not hold locks that block other instances' deliveries.
 *
 * It runs every 50ms rather than continuously: each pass is a commit, and one commit per event
 * would double the database cost of a delivery. The source sees completions up to that much later.
 */
@Component
public class CompletionRelay {
    private static final int BATCH = 500;
    private static final long ORPHAN_AFTER_MS = 10_000;
    private static final long KEEP_SENT_MS = 60_000;

    private final JdbcTemplate db;
    private final KafkaTemplate<String, String> producer;
    private final PartnerSettings settings;
    private final Tracer tracer;
    private long lastPurge;

    public CompletionRelay(JdbcTemplate db, KafkaTemplate<String, String> producer, PartnerSettings settings, Tracer tracer) {
        this.db = db;
        this.producer = producer;
        this.settings = settings;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelay = 50)
    public void relay() {
        String topic = settings.completionTopic();
        if (topic == null || topic.isBlank()) return;
        try {
            long now = System.currentTimeMillis();
            var rows = db.queryForList("SELECT id, seller_id, order_id, payload FROM completion_outbox "
                    + "WHERE sent_at IS NULL AND (owner=? OR created_at<?) ORDER BY id LIMIT ?",
                    tracer.instance(), now - ORPHAN_AFTER_MS, BATCH);
            if (!rows.isEmpty()) {
                List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(rows.size());
                for (var row : rows) {
                    sends.add(producer.send(topic, row.get("seller_id") + ":" + row.get("order_id"), (String) row.get("payload")));
                }
                // Nothing is marked sent unless the whole batch was acknowledged; it is sent again next pass.
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                var ids = rows.stream().map(r -> r.get("id")).toList();
                List<Object> args = new ArrayList<>(ids.size() + 1);
                args.add(System.currentTimeMillis());
                args.addAll(ids);
                db.update("UPDATE completion_outbox SET sent_at=? WHERE id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
                        args.toArray());
            }
            if (now - lastPurge > 1000) {
                lastPurge = now;
                db.update("DELETE FROM completion_outbox WHERE sent_at < ? LIMIT 5000", now - KEEP_SENT_MS);
            }
        } catch (Exception e) {
            tracer.trace("completion_relay_error", null, "error", e.toString());
        }
    }
}
