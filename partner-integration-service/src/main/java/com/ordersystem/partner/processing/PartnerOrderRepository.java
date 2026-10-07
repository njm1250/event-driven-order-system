package com.ordersystem.partner.processing;

import com.ordersystem.common.events.PartnerOrderEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Business ledger of delivered operations; UNIQUE(seller_id, order_id, seq) backs the ordering guard. */
@Repository
public class PartnerOrderRepository {
    private final JdbcTemplate db;

    public PartnerOrderRepository(JdbcTemplate db) {
        this.db = db;
    }

    public boolean isDelivered(String eventId) {
        return db.queryForObject("SELECT COUNT(*) FROM partner_effect WHERE event_id=?", Long.class, eventId) > 0;
    }

    public int lastSequence(String sellerId, long orderId) {
        var values = db.queryForList("SELECT seq FROM partner_order WHERE seller_id=? AND order_id=?",
                Integer.class, sellerId, orderId);
        return values.isEmpty() ? 0 : values.get(0);
    }

    public long deliveredCount() {
        return db.queryForObject("SELECT COUNT(*) FROM partner_effect", Long.class);
    }

    public void recordDelivery(PartnerOrderEvent e, long completedAt) {
        db.update("INSERT INTO partner_effect VALUES(?,?,?,?,?,?,?,?,?)",
                e.eventId(), e.runId(), e.sellerId(), e.orderId(), e.sequence(), e.operation(), e.quantity(), e.price(), completedAt);
        db.update("INSERT INTO partner_order VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                        + "seq=VALUES(seq),operation=VALUES(operation),quantity=VALUES(quantity),price=VALUES(price)",
                e.sellerId(), e.orderId(), e.sequence(), e.operation(), e.quantity(), e.price());
    }
}
