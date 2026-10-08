package com.ordersystem.partner.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.partner.PartnerTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable hand-off between Kafka and the workers. A row exists before its offset is committed, so a
 * crash after the commit cannot lose the event; the primary key absorbs Kafka redelivery.
 *
 * Work ownership: a worker on any instance claims a row together with one of its seller's permits,
 * both under a lease measured on the database clock. Every later write checks that the owner and
 * generation still match and the lease has not run out, so an instance that stalled past its lease
 * can no longer change the row, even if nobody has taken it over yet.
 */
@Repository
public class InboxRepository {
    /** Lease times use the database clock, the one clock every instance shares. */
    static final String NOW_MS = "CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS SIGNED)";

    public enum ClaimResult { CLAIMED, SELLER_FULL, TAKEN, RETRY_BUDGET }

    /** The token an owner presents on every write after the claim. */
    public record Claim(String eventId, String sellerId, String owner, long generation, int slot, boolean retry, boolean reclaimed) {
    }

    public record ClaimAttempt(ClaimResult result, Claim claim) {
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Set<String> sellersWithPermits = Collections.synchronizedSet(new HashSet<>());

    public InboxRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Stores the event unless it is already stored or already delivered. Must run inside the
     * caller's transaction so the capacity check and the insert see the same state.
     *
     * @return false when the event was already in the inbox or delivered
     */
    public boolean store(PartnerTask task, String payload, int pendingLimit, int retainedLimit, long receivedAt) {
        return storeAll(List.of(task), List.of(payload), pendingLimit, retainedLimit, receivedAt) == 1;
    }

    /**
     * Stores one poll's records in the caller's transaction, so the batch shares one commit (and one
     * redo fsync) instead of paying it per record. Events already stored are skipped, and so are
     * events already delivered: their inbox row may have been purged, and a stored copy would wait
     * forever because its sequence is no longer the next one of the order.
     *
     * @return how many rows were inserted
     */
    public int storeAll(List<PartnerTask> tasks, List<String> payloads, int pendingLimit, int retainedLimit, long receivedAt) {
        if (tasks.isEmpty()) return 0;
        // Serializes the capacity check across instances; COUNT then INSERT alone would let two
        // instances both see room for the last free rows.
        db.queryForObject("SELECT id FROM inbox_capacity WHERE id=1 FOR UPDATE", Integer.class);
        var ids = tasks.stream().map(PartnerTask::eventId).toList();
        var placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var known = new HashSet<>(db.queryForList("SELECT event_id FROM inbox WHERE event_id IN (" + placeholders + ")",
                String.class, ids.toArray()));
        known.addAll(db.queryForList("SELECT event_id FROM partner_effect WHERE event_id IN (" + placeholders + ")",
                String.class, ids.toArray()));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var e = task.event();
            if (!known.add(e.eventId())) continue;   // stored or delivered before, or repeated inside this poll
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

    public List<PartnerTask> findReady(long now, int limit) throws Exception {
        return findReady(now, limit, Integer.MAX_VALUE, List.of());
    }

    /**
     * Rows that may be claimed now, oldest first: the next operation of its order, either pending
     * and due or claimed under a lease that has run out. At most {@code sellerPermits} rows per
     * seller, and none of sellers whose permits are all held or that are in {@code skipSellers}
     * (open circuit): a backlog of one seller must not fill the candidate list and hide the others.
     */
    public List<PartnerTask> findReady(long now, int limit, int sellerPermits, Collection<String> skipSellers) throws Exception {
        List<Object> args = new ArrayList<>(List.of(now, sellerPermits));
        String skip = "";
        if (!skipSellers.isEmpty()) {
            skip = " AND i.seller_id NOT IN (" + String.join(",", Collections.nCopies(skipSellers.size(), "?")) + ")";
            args.addAll(skipSellers);
        }
        args.add(sellerPermits);
        args.add(limit);
        var rows = db.queryForList("SELECT * FROM (SELECT i.*, ROW_NUMBER() OVER (PARTITION BY i.seller_id "
                + "ORDER BY i.received_at,i.kafka_offset) AS seller_rank FROM inbox i LEFT JOIN partner_order o "
                + "ON o.seller_id=i.seller_id AND o.order_id=i.order_id "
                + "WHERE ((i.state='PENDING' AND i.next_at<=?) OR (i.state='CLAIMED' AND i.lease_until<" + NOW_MS + ")) "
                + "AND i.seq=COALESCE(o.seq,0)+1 "
                + "AND i.seller_id NOT IN (SELECT p.seller_id FROM seller_permit p WHERE p.owner IS NOT NULL AND p.lease_until>=" + NOW_MS
                + " GROUP BY p.seller_id HAVING COUNT(*)>=?)" + skip
                + ") ready WHERE seller_rank<=? ORDER BY received_at,kafka_offset LIMIT ?", args.toArray());
        List<PartnerTask> tasks = new ArrayList<>(rows.size());
        for (var row : rows) {
            var event = json.readValue((String) row.get("payload"), PartnerOrderEvent.class);
            var task = new PartnerTask(event, null, (String) row.get("topic"),
                    ((Number) row.get("partition_id")).intValue(), ((Number) row.get("kafka_offset")).longValue());
            task.attempts(((Number) row.get("attempts")).intValue());
            task.httpAttempts(task.attempts());
            // Every earlier claim ended without a delivery: given back, or its lease ran out.
            task.failures(((Number) row.get("generation")).intValue());
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * Claims the row and one of its seller's permits in the caller's transaction. The permits of
     * the seller are locked first, the row is skipped if another transaction holds it; that order
     * never waits on a row while holding permits, so it cannot deadlock with a completion.
     */
    public ClaimAttempt claim(String eventId, String sellerId, String owner, int sellerPermits,
                              int retryBudget, long retryWindowMs, long leaseMs) {
        ensurePermits(sellerId, sellerPermits);
        var permits = db.queryForList("SELECT slot, owner IS NULL OR lease_until<" + NOW_MS + " AS free "
                + "FROM seller_permit WHERE seller_id=? ORDER BY slot FOR UPDATE", sellerId);
        Integer slot = permits.stream().filter(p -> ((Number) p.get("free")).intValue() == 1)
                .map(p -> ((Number) p.get("slot")).intValue()).findFirst().orElse(null);
        if (slot == null) return new ClaimAttempt(ClaimResult.SELLER_FULL, null);
        var rows = db.queryForList("SELECT i.generation, i.attempts, i.state FROM inbox i LEFT JOIN partner_order o "
                + "ON o.seller_id=i.seller_id AND o.order_id=i.order_id WHERE i.event_id=? "
                + "AND ((i.state='PENDING' AND i.next_at<=?) OR (i.state='CLAIMED' AND i.lease_until<" + NOW_MS + ")) "
                + "AND i.seq=COALESCE(o.seq,0)+1 FOR UPDATE OF i SKIP LOCKED", eventId, System.currentTimeMillis());
        if (rows.isEmpty()) return new ClaimAttempt(ClaimResult.TAKEN, null);
        long generation = ((Number) rows.get(0).get("generation")).longValue() + 1;
        boolean retry = ((Number) rows.get(0).get("attempts")).intValue() > 0;
        if (retry) {
            long used = db.queryForObject("SELECT COUNT(*) FROM retry_admission WHERE seller_id=? AND admitted_at>"
                    + NOW_MS + "-?", Long.class, sellerId, retryWindowMs);
            if (used >= retryBudget) return new ClaimAttempt(ClaimResult.RETRY_BUDGET, null);
            db.update("INSERT INTO retry_admission(seller_id,event_id,admitted_at) VALUES(?,?," + NOW_MS + ")", sellerId, eventId);
        }
        db.update("UPDATE inbox SET state='CLAIMED',owner=?,generation=?,attempts=attempts+1,lease_until=" + NOW_MS + "+? "
                + "WHERE event_id=?", owner, generation, leaseMs, eventId);
        db.update("UPDATE seller_permit SET owner=?,event_id=?,generation=?,lease_until=" + NOW_MS + "+? "
                + "WHERE seller_id=? AND slot=?", owner, eventId, generation, leaseMs, sellerId, slot);
        boolean reclaimed = "CLAIMED".equals(rows.get(0).get("state"));
        return new ClaimAttempt(ClaimResult.CLAIMED, new Claim(eventId, sellerId, owner, generation, slot, retry, reclaimed));
    }

    private void ensurePermits(String sellerId, int count) {
        if (sellersWithPermits.contains(sellerId)) return;
        for (int slot = 1; slot <= count; slot++) {
            db.update("INSERT IGNORE INTO seller_permit(seller_id,slot) VALUES(?,?)", sellerId, slot);
        }
        sellersWithPermits.add(sellerId);
    }

    /**
     * Extends the leases of the given claims that are still valid.
     *
     * @return the claims whose lease could not be extended: the owner has lost them
     */
    public List<Claim> renew(Collection<Claim> claims, long leaseMs) {
        List<Claim> lost = new ArrayList<>();
        for (var claim : claims) {
            int rows = db.update("UPDATE inbox SET lease_until=" + NOW_MS + "+? WHERE event_id=? AND owner=? "
                    + "AND generation=? AND state='CLAIMED' AND lease_until>=" + NOW_MS, leaseMs, claim.eventId(), claim.owner(), claim.generation());
            if (rows == 1) {
                db.update("UPDATE seller_permit SET lease_until=" + NOW_MS + "+? WHERE seller_id=? AND slot=? AND owner=? "
                        + "AND event_id=? AND generation=?", leaseMs, claim.sellerId(), claim.slot(), claim.owner(), claim.eventId(), claim.generation());
            } else {
                lost.add(claim);
            }
        }
        return lost;
    }

    /**
     * Locks the row and checks the token in the caller's transaction, before the delivery is
     * recorded. An expired lease fails the check even when no one has claimed the row since.
     */
    public boolean holds(Claim claim) {
        var rows = db.queryForList("SELECT 1 FROM inbox WHERE event_id=? AND owner=? AND generation=? AND state='CLAIMED' "
                + "AND lease_until>=" + NOW_MS + " FOR UPDATE", claim.eventId(), claim.owner(), claim.generation());
        return !rows.isEmpty();
    }

    /** Marks the claimed row delivered and frees the permit, in the caller's transaction after {@link #holds}. */
    public void complete(Claim claim, long doneAt) {
        db.update("UPDATE inbox SET state='DONE',done_at=?,owner=NULL,lease_until=0 WHERE event_id=? AND owner=? AND generation=?",
                doneAt, claim.eventId(), claim.owner(), claim.generation());
        release(claim);
    }

    /** Puts the row back for a later attempt if the token still holds; frees the permit either way it can. */
    public boolean giveBack(Claim claim, long nextAt) {
        int rows = db.update("UPDATE inbox SET state='PENDING',owner=NULL,lease_until=0,next_at=? WHERE event_id=? AND owner=? "
                + "AND generation=? AND state='CLAIMED' AND lease_until>=" + NOW_MS, nextAt, claim.eventId(), claim.owner(), claim.generation());
        release(claim);
        return rows == 1;
    }

    /** Frees the permit only while this claim still holds it; a stale owner cannot free someone else's. */
    public void release(Claim claim) {
        db.update("UPDATE seller_permit SET owner=NULL,event_id=NULL,lease_until=0 WHERE seller_id=? AND slot=? AND owner=? "
                + "AND event_id=? AND generation=?", claim.sellerId(), claim.slot(), claim.owner(), claim.eventId(), claim.generation());
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

    /** Retry admissions older than the window no longer count. */
    public int deleteRetryAdmissionsBefore(long windowMs, int limit) {
        return db.update("DELETE FROM retry_admission WHERE admitted_at<" + NOW_MS + "-? LIMIT ?", windowMs * 10, limit);
    }

    public static class InboxFullException extends IllegalStateException {
        public InboxFullException() {
            super("Inbox capacity reached");
        }
    }
}
