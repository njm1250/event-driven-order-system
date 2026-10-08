package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.dispatch.RetryBackoff;
import com.ordersystem.partner.processing.DeliveryDeferredException;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import com.ordersystem.partner.processing.SellerCircuitBreakers;
import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import io.confluent.parallelconsumer.RecordContext;
import io.confluent.parallelconsumer.internal.AbstractParallelEoSStreamProcessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ordersystem.partner.processing.DeliveryDeferredException.Reason.CIRCUIT_OPEN;

/**
 * Confluent Parallel Consumer: records with different keys run concurrently, records with the
 * same key run in order, and failed records are retried without blocking others.
 *
 * PARALLEL_CONSUMER uses the library defaults with no per-seller limit, to measure the library as
 * it is commonly adopted. KAFKA_BUCKET relies on the producer routing each seller to a fixed number
 * of key buckets, so the bucket count is the seller's concurrency, and adds the same circuit
 * breaker, backoff and per-seller retry budget as the other candidates. A record that may not call
 * now (open circuit, no retry budget) fails without a call and is retried after the backoff.
 */
@Component
public class ParallelConsumerRunner implements SmartLifecycle {
    private final PartnerSettings settings;
    private final ConsumerFactory<String, String> consumerFactory;
    private final ObjectMapper json;
    private final PartnerOrderProcessor processor;
    private final SellerCircuitBreakers breakers;
    private final Tracer tracer;
    private final String group;
    private final RetryBackoff backoff;
    private final AtomicInteger assigned = new AtomicInteger();
    // Partner calls already started per event, to tell a retry from a first call after a deferral.
    private final Map<String, Integer> httpAttempts = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> retryAdmissions = new HashMap<>();
    private ParallelStreamProcessor<String, String> parallel;

    public ParallelConsumerRunner(PartnerSettings settings, ConsumerFactory<String, String> consumerFactory,
                                  ObjectMapper json, PartnerOrderProcessor processor, SellerCircuitBreakers breakers,
                                  Tracer tracer, @Value("${spring.kafka.consumer.group-id}") String group) {
        this.settings = settings;
        this.consumerFactory = consumerFactory;
        this.json = json;
        this.processor = processor;
        this.breakers = breakers;
        this.tracer = tracer;
        this.group = group;
        this.backoff = RetryBackoff.of(settings);
    }

    public int assignedPartitions() {
        return assigned.get();
    }

    /** Records the library holds that are not yet complete (queued, running or waiting for a retry). */
    public Long workRemaining() {
        return parallel instanceof AbstractParallelEoSStreamProcessor<?, ?> processor ? processor.workRemaining() : null;
    }

    @Override
    public void start() {
        if (!settings.mode().usesParallelConsumer()) return;
        boolean candidate = settings.mode() == ProcessingMode.KAFKA_BUCKET;
        var builder = ParallelConsumerOptions.<String, String>builder()
                .ordering(ParallelConsumerOptions.ProcessingOrder.KEY)
                .maxConcurrency(settings.workers())
                .commitMode(ParallelConsumerOptions.CommitMode.PERIODIC_CONSUMER_SYNC)
                .consumer(plainConsumer());
        if (candidate) builder.retryDelayProvider(this::retryDelay);
        // Records the library may hold per worker. With its default it stops fetching at a few dozen
        // records, which a stuck seller fills in seconds; the comparison sets it to match the inbox limit.
        if (settings.parallelLoadFactor() > 0) {
            builder.initialLoadFactor(settings.parallelLoadFactor()).maximumLoadFactor(settings.parallelLoadFactor());
        }
        else builder.defaultMessageRetryDelay(Duration.ofMillis(settings.retryDelayMs()));
        parallel = ParallelStreamProcessor.createEosStreamProcessor(builder.build());
        parallel.subscribe(List.of(settings.topic()), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                assigned.addAndGet(-partitions.size());
                tracer.trace("partitions_revoked", null, "partitions", partitions.toString());
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                assigned.addAndGet(partitions.size());
                tracer.trace("partitions_assigned", null, "partitions", partitions.toString());
            }
        });
        parallel.poll(context -> {
            var record = context.getSingleConsumerRecord();
            PartnerOrderEvent event;
            try {
                event = json.readValue(record.value(), PartnerOrderEvent.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            var task = new PartnerTask(event, null, record.topic(), record.partition(), record.offset());
            task.attempts(context.getSingleRecord().getNumberOfFailedAttempts());
            tracer.trace("received", task);
            try {
                if (candidate) processUnderPolicy(task);
                else {
                    task.nextAttempt();
                    processor.process(task);
                }
            } catch (Exception e) {
                tracer.trace("retry_scheduled", task, "error", e.toString());
                throw new IllegalStateException(e);
            }
            BoundaryGate.hit("business_commit", task.eventId());
        });
    }

    private void processUnderPolicy(PartnerTask task) throws Exception {
        String seller = task.sellerId();
        int started = httpAttempts.getOrDefault(task.eventId(), 0);
        task.httpAttempts(started);
        if (!breakers.tryAcquire(seller)) {
            tracer.trace("circuit_open", task, "state", breakers.state(seller).name());
            throw new DeliveryDeferredException(CIRCUIT_OPEN, "Circuit open for seller " + seller);
        }
        if (started > 0 && !tryRetryAdmission(seller)) {
            breakers.release(seller);
            tracer.trace("retry_budget_wait", task);
            throw new DeliveryDeferredException(CIRCUIT_OPEN, "Retry budget spent for seller " + seller);
        }
        if (started > 0) tracer.trace("retry_admitted", task);
        try {
            processor.process(task, null, true);
            httpAttempts.remove(task.eventId());
        } finally {
            if (task.httpAttempts() > started) httpAttempts.put(task.eventId(), task.httpAttempts());
        }
    }

    /** Per-seller rolling window, local to this instance; Kafka key ownership does not make it global. */
    private synchronized boolean tryRetryAdmission(String seller) {
        long now = System.currentTimeMillis();
        var times = retryAdmissions.computeIfAbsent(seller, s -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() <= now - settings.retryWindowMs()) times.removeFirst();
        if (times.size() >= settings.retryBudget()) return false;
        times.addLast(now);
        return true;
    }

    private Duration retryDelay(RecordContext<String, String> context) {
        String eventId;
        try {
            eventId = json.readTree(context.value()).path("eventId").asText();
        } catch (Exception e) {
            eventId = context.topic() + "/" + context.partition() + "/" + context.offset();
        }
        return Duration.ofMillis(backoff.delayMs(eventId, context.getNumberOfFailedAttempts()));
    }

    /** A plain KafkaConsumer: the library inspects its auto-commit setting reflectively. */
    private KafkaConsumer<String, String> plainConsumer() {
        Map<String, Object> config = new HashMap<>(consumerFactory.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // The partner interceptor prints poll boundaries for the listener modes only.
        config.remove(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG);
        return new KafkaConsumer<>(config);
    }

    @Override
    public void stop() {
        if (parallel != null) parallel.close();
        parallel = null;
    }

    @Override
    public boolean isRunning() {
        return parallel != null;
    }
}
