package com.ordersystem.partner.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.dispatch.AdmissionPolicy;
import com.ordersystem.partner.dispatch.AsyncPendingQueue;
import com.ordersystem.partner.kafka.ParallelConsumerRunner;
import com.ordersystem.partner.kafka.PartnerListener;
import com.ordersystem.partner.kafka.RetryLane;
import com.ordersystem.partner.processing.SellerCircuitBreakers;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import jakarta.annotation.PreDestroy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Endpoints used only by the experiment controller: observation for the independent collector,
 * workload input and the shared DB pool fault. Not part of the partner integration itself.
 */
@RestController
public class ExperimentController {
    private final PartnerSettings settings;
    private final ObjectMapper json;
    private final Tracer tracer;
    private final HikariDataSource pool;
    private final KafkaTemplate<String, String> producer;
    private final KafkaListenerEndpointRegistry registry;
    private final PartnerListener listener;
    private final AsyncPendingQueue pending;
    private final AdmissionPolicy admission;
    private final RetryLane retryLane;
    private final SellerCircuitBreakers breakers;
    private final ParallelConsumerRunner parallel;
    private final String group;
    private final String bootstrap;
    private AdminClient admin;
    private Connection accounting;

    public ExperimentController(PartnerSettings settings, ObjectMapper json, Tracer tracer, HikariDataSource pool,
                                KafkaTemplate<String, String> producer, KafkaListenerEndpointRegistry registry,
                                PartnerListener listener, AsyncPendingQueue pending, AdmissionPolicy admission,
                                RetryLane retryLane, SellerCircuitBreakers breakers, ParallelConsumerRunner parallel,
                                @Value("${spring.kafka.consumer.group-id}") String group,
                                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.settings = settings;
        this.json = json;
        this.tracer = tracer;
        this.pool = pool;
        this.producer = producer;
        this.registry = registry;
        this.listener = listener;
        this.pending = pending;
        this.admission = admission;
        this.retryLane = retryLane;
        this.breakers = breakers;
        this.parallel = parallel;
        this.group = group;
        this.bootstrap = bootstrap;
    }

    @GetMapping("/observe")
    public Map<String, Object> observe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("time", System.currentTimeMillis());
        result.put("instance", tracer.instance());
        result.put("mode", settings.mode().label());
        result.put("receivedOffsets", listener.receivedOffsets());
        result.put("active", admission.active());
        result.put("traceCount", tracer.count());
        result.put("sellerActive", admission.activeBySeller());
        result.put("memoryPending", pending.size());
        result.put("parkedOrders", retryLane.parkedOrders());
        result.put("circuits", breakers.states());
        var mx = pool.getHikariPoolMXBean();
        result.put("dbActive", mx.getActiveConnections());
        result.put("dbWaiting", mx.getThreadsAwaitingConnection());
        result.put("heapUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        result.put("cpuNanos", ProcessHandle.current().info().totalCpuDuration().map(Duration::toNanos).orElse(0L));
        long gcCount = 0, gcMillis = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        result.put("gcCount", gcCount);
        result.put("gcMillis", gcMillis);
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        // Producer-side Kafka latency: covers input sends and retry topic parking (acks=all).
        Map<String, Object> producerMetrics = new LinkedHashMap<>();
        producer.metrics().forEach((name, metric) -> {
            if (name.group().equals("producer-metrics") && Set.of("request-latency-avg", "request-latency-max",
                    "record-queue-time-avg", "record-send-rate").contains(name.name())) {
                producerMetrics.put(name.name(), metric.metricValue());
            }
        });
        result.put("producer", producerMetrics);
        var container = registry.getListenerContainer("partner");
        result.put("paused", container != null && container.isContainerPaused());
        if (settings.mode() == ProcessingMode.PARALLEL_CONSUMER) result.put("assigned", parallel.assignedPartitions());
        else result.put("assigned", container == null || container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size());
        if (container != null) {
            var lag = container.metrics().values().stream().flatMap(m -> m.entrySet().stream())
                    .filter(x -> x.getKey().name().equals("records-lag-max"))
                    .map(x -> x.getValue().metricValue()).findFirst();
            result.put("recordsLagMax", lag.orElse(null));
        }
        try {
            var admin = admin();
            var mainPartitions = partitions(settings.topic(), settings.partitions());
            var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
            var ends = endOffsets(admin, mainPartitions);
            var first = committed.get(mainPartitions.get(0));
            long remaining = 0;
            boolean known = true;
            for (var partition : mainPartitions) {
                var offset = committed.get(partition);
                if (offset == null) { known = false; continue; }
                remaining += ends.get(partition) - offset.offset();
            }
            if (settings.mode() == ProcessingMode.RETRY_TOPIC) {
                var retry = new TopicPartition(settings.retryTopic(), 0);
                var retryCommitted = admin.listConsumerGroupOffsets(group + "-retry").partitionsToOffsetAndMetadata()
                        .get(2, TimeUnit.SECONDS).get(retry);
                long retryEnd = endOffsets(admin, List.of(retry)).get(retry);
                long retryRemaining = retryEnd - (retryCommitted == null ? 0 : retryCommitted.offset());
                result.put("retryRemaining", retryRemaining);
                result.put("retryLogEnd", retryEnd);
                remaining += retryRemaining;
            }
            result.put("brokerCommitted", first == null ? null : first.offset());
            result.put("logEnd", ends.get(mainPartitions.get(0)));
            result.put("committedRemaining", known ? remaining : null);
        } catch (Exception e) {
            result.put("brokerError", e.toString());
            result.put("brokerCommitted", null);
            result.put("committedRemaining", null);
        }
        return result;
    }

    @PostMapping("/load")
    public synchronized List<Map<String, Object>> load(@RequestBody List<PartnerOrderEvent> events) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        producer.partitionsFor(settings.topic());
        // inputBudget <= 0 disables the check: throughput sweeps measure the structures, not this guard.
        if (!events.isEmpty() && settings.inputBudget() > 0) {
            long produced = endOffsets(admin(), partitions(settings.topic(), settings.partitions()))
                    .values().stream().mapToLong(Long::longValue).sum();
            if (produced - deliveredCount() + events.size() > settings.inputBudget()) {
                throw new IllegalStateException("Global input backlog budget (" + settings.inputBudget() + ") reached");
            }
        }
        if (events.size() > settings.backlogLimit()) throw new IllegalArgumentException("Input batch exceeds capacity");
        // Send the whole batch before waiting, so high input rates are not limited by one ack round trip
        // per event. The idempotent producer keeps per-partition order.
        List<CompletableFuture<SendResult<String, String>>> pending = new ArrayList<>();
        for (var e : events) {
            e.validate();
            String value = json.writeValueAsString(e);
            // One partition: pin it, so both sellers share it. Several: let the key hash decide.
            pending.add(settings.partitions() == 1
                    ? producer.send(settings.topic(), 0, e.key(), value)
                    : producer.send(settings.topic(), e.key(), value));
        }
        for (int i = 0; i < events.size(); i++) {
            var sent = pending.get(i).get(5, TimeUnit.SECONDS).getRecordMetadata();
            var e = events.get(i);
            result.add(Map.of("eventId", e.eventId(), "sellerId", e.sellerId(), "partition", sent.partition(), "offset", sent.offset()));
        }
        return result;
    }

    @PostMapping("/fault/db")
    public Map<String, Object> occupyConnections(@RequestBody Map<String, Integer> request) {
        int duration = request.getOrDefault("durationMs", 2000);
        if (duration < 1 || duration > 15000) throw new IllegalArgumentException("duration 1..15000");
        for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
            Thread thread = new Thread(() -> {
                try (var connection = pool.getConnection()) {
                    tracer.trace("connection_occupied", null);
                    Thread.sleep(duration);
                } catch (Exception e) {
                    tracer.trace("fault_error", null, "error", e.toString());
                }
            }, "db-fault");
            thread.setDaemon(true);
            thread.start();
        }
        return Map.of("accepted", true, "durationMs", duration);
    }

    /**
     * Uses its own connection: the input controller must work while the worker pool is faulted.
     * Kept open: opening one per call means a TLS handshake each time.
     */
    private synchronized long deliveredCount() throws Exception {
        if (accounting == null || !accounting.isValid(1)) {
            accounting = DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        }
        try (var statement = accounting.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM partner_effect")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** One client for the process; creating one per call is expensive. */
    private synchronized AdminClient admin() {
        if (admin == null) admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        return admin;
    }

    @PreDestroy
    void close() throws Exception {
        if (admin != null) admin.close();
        if (accounting != null) accounting.close();
    }

    private static List<TopicPartition> partitions(String topic, int count) {
        List<TopicPartition> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new TopicPartition(topic, i));
        return result;
    }

    private static Map<TopicPartition, Long> endOffsets(AdminClient admin, List<TopicPartition> partitions) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(p -> request.put(p, OffsetSpec.latest()));
        Map<TopicPartition, Long> result = new HashMap<>();
        admin.listOffsets(request).all().get(2, TimeUnit.SECONDS).forEach((p, info) -> result.put(p, info.offset()));
        return result;
    }
}
