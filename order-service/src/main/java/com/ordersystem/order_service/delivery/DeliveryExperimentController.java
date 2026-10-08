package com.ordersystem.order_service.delivery;

import com.ordersystem.common.events.PartnerOrderEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints for the load experiments only. A load generator on another host submits order
 * operations here at their planned times; each is accepted in its own source transaction with the
 * outbox write, as an order mutation would be. Not part of the order API.
 */
@RestController
@RequestMapping("/experiment")
@ConditionalOnProperty(name = "app.delivery-tracking", havingValue = "true")
public class DeliveryExperimentController {
    public record Submission(PartnerOrderEvent event, String routingKey) {
    }

    private final DeliveryObligations obligations;
    private final DeliveryBacklogMonitor monitor;
    private final TransactionTemplate tx;
    private final String topic;

    public DeliveryExperimentController(DeliveryObligations obligations, DeliveryBacklogMonitor monitor,
                                        TransactionTemplate tx, @Value("${app.partner-topic}") String topic) {
        this.obligations = obligations;
        this.monitor = monitor;
        this.tx = tx;
        this.topic = topic;
    }

    @PostMapping("/obligations")
    public Map<String, Object> accept(@RequestBody Submission submission) {
        submission.event().validate();
        Long createdAt = tx.execute(status -> obligations.accept(submission.event(), submission.routingKey(), topic));
        return Map.of("eventId", submission.event().eventId(), "createdAt", createdAt);
    }

    @GetMapping("/observe")
    public Map<String, Object> observe() {
        var snapshot = monitor.latest();
        Map<String, Object> sellers = new LinkedHashMap<>();
        snapshot.sellers().forEach((seller, b) -> sellers.put(seller, Map.of(
                "oldestAgeMs", snapshot.takenAt() - b.oldestCreatedAt(), "unresolved", b.unresolved(), "overdue", b.overdue())));
        return Map.of("time", System.currentTimeMillis(), "snapshotAt", snapshot.takenAt(),
                "targetMs", monitor.targetMs(), "sellers", sellers);
    }

    @PostMapping("/observe/pause")
    public Map<String, Object> pause(@RequestBody Map<String, Long> request) {
        long duration = request.getOrDefault("durationMs", 20_000L);
        monitor.pause(duration);
        return Map.of("pausedMs", duration, "time", System.currentTimeMillis());
    }

    @GetMapping("/obligations")
    public List<Map<String, Object>> dump() {
        return obligations.dump();
    }
}
