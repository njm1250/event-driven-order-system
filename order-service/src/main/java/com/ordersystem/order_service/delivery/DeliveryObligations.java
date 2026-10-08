package com.ordersystem.order_service.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A delivery obligation is opened when the source accepts an order operation and closed when the
 * partner integration reports the delivery. It is the user-facing view of "accepted but not yet at
 * the seller", independent of where the work waits (outbox, Kafka, inbox, retry topic).
 */
@Repository
public class DeliveryObligations {
    public record SellerBacklog(long oldestCreatedAt, long unresolved, long overdue) {
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public DeliveryObligations(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Opens the obligation and writes the partner request to the outbox, in the caller's
     * transaction. The obligation time is taken here, inside the transaction, so source commit cost
     * counts toward delivery latency. A repeated request keeps the first acceptance.
     *
     * @return the time the obligation was opened
     */
    public long accept(PartnerOrderEvent request, String routingKey, String topic) {
        long createdAt = System.currentTimeMillis();
        int inserted = db.update("INSERT IGNORE INTO delivery_obligation(event_id,run_id,seller_id,order_id,seq,operation,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", request.eventId(), request.runId(), request.sellerId(), request.orderId(),
                request.sequence(), request.operation(), createdAt);
        if (inserted == 0) {
            return db.queryForObject("SELECT created_at FROM delivery_obligation WHERE event_id=?", Long.class, request.eventId());
        }
        var event = new PartnerOrderEvent(request.eventId(), request.runId(), request.sellerId(), request.orderId(),
                request.sequence(), request.operation(), createdAt, 1, request.quantity(), request.price(), request.padding());
        event.validate();
        db.update("INSERT INTO outbox_event(event_id,aggregate_id,topic,event_type,payload,status,created_at) "
                        + "VALUES(?,?,?,?,?,'PENDING',NOW(6))",
                event.eventId(), routingKey, topic, PartnerOrderEvent.class.getName(), serialize(event));
        return createdAt;
    }

    /** Closes the obligation once; later reports of the same delivery change nothing. */
    public int resolve(String eventId, long completedAt) {
        return db.update("UPDATE delivery_obligation SET resolved_at=?,completed_at=? WHERE event_id=? AND resolved_at IS NULL",
                System.currentTimeMillis(), completedAt, eventId);
    }

    /** Unresolved obligations per seller; overdue means older than the delivery target. */
    public Map<String, SellerBacklog> backlog(long now, long targetMs) {
        Map<String, SellerBacklog> result = new LinkedHashMap<>();
        db.query("SELECT seller_id, MIN(created_at) AS oldest, COUNT(*) AS unresolved, SUM(created_at < ?) AS overdue "
                        + "FROM delivery_obligation WHERE resolved_at IS NULL GROUP BY seller_id",
                row -> {
                    result.put(row.getString("seller_id"),
                            new SellerBacklog(row.getLong("oldest"), row.getLong("unresolved"), row.getLong("overdue")));
                }, now - targetMs);
        return result;
    }

    public List<Map<String, Object>> dump() {
        return db.queryForList("SELECT * FROM delivery_obligation");
    }

    private String serialize(PartnerOrderEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
