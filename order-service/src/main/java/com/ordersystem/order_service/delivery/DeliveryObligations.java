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

    /** createdAt is null when the operation was not accepted; reason says why. */
    public record Acceptance(Long createdAt, String reason) {
        static Acceptance rejected(String reason) {
            return new Acceptance(null, reason);
        }
    }

    static final int OPERATIONS_PER_ORDER = 3;

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
     * With a seller quota, a new order reserves room for all three of its operations and is
     * refused when the seller's reserved and undelivered operations would exceed the quota; changes
     * and cancellations of an accepted order are always taken, those of a refused order never.
     */
    public Acceptance accept(PartnerOrderEvent request, String routingKey, String topic, int sellerQuota) {
        var existing = db.queryForList("SELECT created_at FROM delivery_obligation WHERE event_id=?", Long.class, request.eventId());
        if (!existing.isEmpty()) return new Acceptance(existing.get(0), null);
        if (sellerQuota > 0) {
            if (request.sequence() == 1) {
                db.update("INSERT IGNORE INTO seller_quota(seller_id,used) VALUES(?,0)", request.sellerId());
                int used = db.queryForObject("SELECT used FROM seller_quota WHERE seller_id=? FOR UPDATE", Integer.class, request.sellerId());
                if (used + OPERATIONS_PER_ORDER > sellerQuota) return Acceptance.rejected("seller quota");
                db.update("UPDATE seller_quota SET used=used+? WHERE seller_id=?", OPERATIONS_PER_ORDER, request.sellerId());
            } else if (db.queryForList("SELECT 1 FROM delivery_obligation WHERE seller_id=? AND order_id=? AND seq=1",
                    request.sellerId(), request.orderId()).isEmpty()) {
                return Acceptance.rejected("order not accepted");
            }
        }
        long createdAt = System.currentTimeMillis();
        db.update("INSERT INTO delivery_obligation(event_id,run_id,seller_id,order_id,seq,operation,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", request.eventId(), request.runId(), request.sellerId(), request.orderId(),
                request.sequence(), request.operation(), createdAt);
        var event = new PartnerOrderEvent(request.eventId(), request.runId(), request.sellerId(), request.orderId(),
                request.sequence(), request.operation(), createdAt, 1, request.quantity(), request.price(), request.padding());
        event.validate();
        db.update("INSERT INTO outbox_event(event_id,aggregate_id,topic,event_type,payload,status,created_at) "
                        + "VALUES(?,?,?,?,?,'PENDING',NOW(6))",
                event.eventId(), routingKey, topic, PartnerOrderEvent.class.getName(), serialize(event));
        return new Acceptance(createdAt, null);
    }

    /** Closes the obligation once and returns its quota share; later reports of the same delivery change nothing. */
    public int resolve(String eventId, String sellerId, long completedAt) {
        int closed = db.update("UPDATE delivery_obligation SET resolved_at=?,completed_at=? WHERE event_id=? AND resolved_at IS NULL",
                System.currentTimeMillis(), completedAt, eventId);
        if (closed == 1) db.update("UPDATE seller_quota SET used=GREATEST(used-1,0) WHERE seller_id=?", sellerId);
        return closed;
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
