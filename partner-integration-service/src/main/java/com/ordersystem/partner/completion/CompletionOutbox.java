package com.ordersystem.partner.completion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerCompletionEvent;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.UncheckedIOException;

/**
 * Completion reports for the source, written in the same transaction as the delivery record. The
 * source learns about a delivery even if this process dies right after the commit.
 */
@Repository
public class CompletionOutbox {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String owner;

    public CompletionOutbox(JdbcTemplate db, ObjectMapper json, PartnerSettings settings, Tracer tracer) {
        this.db = db;
        this.json = json;
        this.owner = tracer.instance();
        this.enabled = settings.completionTopic() != null && !settings.completionTopic().isBlank();
    }

    /** Call inside the delivery transaction. */
    public void record(PartnerOrderEvent event, long completedAt) {
        if (!enabled) return;
        try {
            String payload = json.writeValueAsString(new PartnerCompletionEvent(event.eventId(), event.sellerId(),
                    event.orderId(), event.sequence(), completedAt));
            db.update("INSERT IGNORE INTO completion_outbox(event_id,seller_id,order_id,payload,owner,created_at) VALUES(?,?,?,?,?,?)",
                    event.eventId(), event.sellerId(), event.orderId(), payload, owner, completedAt);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
