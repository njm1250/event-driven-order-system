package com.ordersystem.order_service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @org.springframework.data.jpa.repository.Query(value="SELECT id FROM outbox_capacity WHERE id=1 FOR UPDATE", nativeQuery=true)
    Integer lockCapacity();

    @org.springframework.data.jpa.repository.Query(value="SELECT (SELECT COUNT(*) FROM orders WHERE order_status='PENDING') + (SELECT COUNT(*) FROM outbox_event o WHERE o.topic='partner-order-requests' AND NOT EXISTS (SELECT 1 FROM partner_db.partner_effect e WHERE e.event_id=o.event_id))", nativeQuery=true)
    long countUnfinishedBusiness();

    List<OutboxEvent> findTop500ByStatusOrderByIdAsc(OutboxEvent.Status status);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(value="UPDATE outbox_event SET status='SENT', sent_at=:sentAt WHERE id IN (:ids)", nativeQuery=true)
    int markSent(@org.springframework.data.repository.query.Param("ids") List<Long> ids,
                 @org.springframework.data.repository.query.Param("sentAt") java.time.LocalDateTime sentAt);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(value="DELETE FROM outbox_event WHERE status='SENT' AND sent_at < :before LIMIT 5000", nativeQuery=true)
    int deleteSentBefore(@org.springframework.data.repository.query.Param("before") java.time.LocalDateTime before);
}
