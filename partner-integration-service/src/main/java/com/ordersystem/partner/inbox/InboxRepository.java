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

    /**
     * Stores one poll's records in the caller's transaction, so the batch shares one commit (and one
     * redo fsync) instead of paying it per record. Already stored events are skipped.
     *
     * @return how many rows were inserted
     */
    public int storeAll(List<PartnerTask> tasks, List<String> payloads, int pendingLimit, int retainedLimit, long receivedAt) {
        if (tasks.isEmpty()) return 0;
        var ids = tasks.stream().map(PartnerTask::eventId).toList();
        var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var existing = new java.util.HashSet<>(db.queryForList("SELECT event_id FROM inbox WHERE event_id IN (" + placeholders + ")",
                String.class, ids.toArray()));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var e = task.event();
            if (!existing.add(e.eventId())) continue;   // stored before, or repeated inside this poll
            rows.add(new Object[]{e.eventId(), e.runId(), e.sellerId(), e.orderId(), e.sequence(), payloads.get(i),
                    task.topic(), task.partition(), task.offset(), receivedAt});
        }
        if (rows.isEmpty()) return 0;
        if (db.queryForObject("SELECT COUNT(*) FROM inbox WHERE state<>'DONE'", Long.class) + rows.size() > pendingLimit
                || db.queryForObject("SELECT COUNT(*) FROM inbox", Long.class) + rows.size() > retainedLimit) {
            throw new InboxFullException();
        }
        db.batchUpdate("INSERT INTO inbox(event_id,run_id,seller_id,order_id,seq,payload,topic,partition_id,kafka_offset,received_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)", rows);
        return rows.size();
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

    /** Records the failed attempt together with the next retry time in one statement. */
    public void scheduleRetry(String eventId, long nextAt, int attempts) {
        db.update("UPDATE inbox SET next_at=?,attempts=GREATEST(attempts,?) WHERE event_id=?", nextAt, attempts, eventId);
    }

    public void markDone(String eventId, long doneAt, int attempts) {
        db.update("UPDATE inbox SET state='DONE',done_at=?,attempts=GREATEST(attempts,?) WHERE event_id=?", doneAt, attempts, eventId);
    }

    /** Completed rows are only history; the delivery ledger stays the duplicate guard after deletion. */
    public int deleteDoneBefore(long cutoff, int limit) {
        return db.update("DELETE FROM inbox WHERE state='DONE' AND done_at < ? LIMIT ?", cutoff, limit);
    }

    public static class InboxFullException extends IllegalStateException {
        public InboxFullException() {
            super("Inbox capacity reached");
        }
    }
}
