package com.ordersystem.partner.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.partner.PartnerTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable hand-off between Kafka and the workers. A row exists before its offset is committed, so a
 * crash after the commit cannot lose the event; the primary key absorbs Kafka redelivery.
 */
@Repository
public class InboxRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public InboxRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Stores the event unless it is already stored. Must run inside the caller's transaction so the
     * capacity check and the insert see the same state.
     *
     * @return false when the event was already in the inbox
     */
    public boolean store(PartnerTask task, String payload, int pendingLimit, int retainedLimit, long receivedAt) {
        var e = task.event();
        if (db.queryForObject("SELECT COUNT(*) FROM inbox WHERE event_id=?", Long.class, e.eventId()) > 0) return false;
        if (db.queryForObject("SELECT COUNT(*) FROM inbox WHERE state<>'DONE'", Long.class) >= pendingLimit
                || db.queryForObject("SELECT COUNT(*) FROM inbox", Long.class) >= retainedLimit) {
            throw new InboxFullException();
        }
        db.update("INSERT INTO inbox(event_id,run_id,seller_id,order_id,seq,payload,topic,partition_id,kafka_offset,received_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                e.eventId(), e.runId(), e.sellerId(), e.orderId(), e.sequence(), payload,
                task.topic(), task.partition(), task.offset(), receivedAt);
        return true;
    }

    /** Pending rows whose predecessor is delivered and whose retry delay has passed, oldest first. */
    public List<PartnerTask> findReady(long now, int limit) throws Exception {
        var rows = db.queryForList("SELECT i.* FROM inbox i LEFT JOIN partner_order o "
                + "ON o.seller_id=i.seller_id AND o.order_id=i.order_id "
                + "WHERE i.state='PENDING' AND i.seq=COALESCE(o.seq,0)+1 AND i.next_at<=? "
                + "ORDER BY i.received_at,i.kafka_offset LIMIT ?", now, limit);
        List<PartnerTask> tasks = new ArrayList<>(rows.size());
        for (var row : rows) {
            var event = json.readValue((String) row.get("payload"), PartnerOrderEvent.class);
            var task = new PartnerTask(event, null, (String) row.get("topic"),
                    ((Number) row.get("partition_id")).intValue(), ((Number) row.get("kafka_offset")).longValue());
            task.attempts(((Number) row.get("attempts")).intValue());
            tasks.add(task);
        }
        return tasks;
    }

    public void saveAttempts(String eventId, int attempts) {
        db.update("UPDATE inbox SET attempts=? WHERE event_id=?", attempts, eventId);
    }

    public void scheduleRetry(String eventId, long nextAt) {
        db.update("UPDATE inbox SET next_at=? WHERE event_id=?", nextAt, eventId);
    }

    public void markDone(String eventId, long doneAt) {
        db.update("UPDATE inbox SET state='DONE',done_at=? WHERE event_id=?", doneAt, eventId);
    }

    public static class InboxFullException extends IllegalStateException {
        public InboxFullException() {
            super("Inbox capacity reached");
        }
    }
}
