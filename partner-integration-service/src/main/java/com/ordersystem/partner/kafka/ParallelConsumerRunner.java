package com.ordersystem.partner.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerOrderEvent;
import com.ordersystem.common.experiment.BoundaryGate;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.processing.PartnerOrderProcessor;
import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.ParallelStreamProcessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Confluent Parallel Consumer with its defaults: records with different keys run concurrently,
 * records with the same key run in order, and failed records are retried without blocking others.
 * There is deliberately no per-seller limit, to measure the library as it is commonly adopted.
 */
@Component
public class ParallelConsumerRunner implements SmartLifecycle {
    private final PartnerSettings settings;
    private final ConsumerFactory<String, String> consumerFactory;
    private final ObjectMapper json;
    private final PartnerOrderProcessor processor;
    private final Tracer tracer;
    private final String group;
    private final AtomicInteger assigned = new AtomicInteger();
    private ParallelStreamProcessor<String, String> parallel;

    public ParallelConsumerRunner(PartnerSettings settings, ConsumerFactory<String, String> consumerFactory,
                                  ObjectMapper json, PartnerOrderProcessor processor, Tracer tracer,
                                  @Value("${spring.kafka.consumer.group-id}") String group) {
        this.settings = settings;
        this.consumerFactory = consumerFactory;
        this.json = json;
        this.processor = processor;
        this.tracer = tracer;
        this.group = group;
    }

    public int assignedPartitions() {
        return assigned.get();
    }

    @Override
    public void start() {
        if (settings.mode() != ProcessingMode.PARALLEL_CONSUMER) return;
        var options = ParallelConsumerOptions.<String, String>builder()
                .ordering(ParallelConsumerOptions.ProcessingOrder.KEY)
                .maxConcurrency(settings.workers())
                .commitMode(ParallelConsumerOptions.CommitMode.PERIODIC_CONSUMER_SYNC)
                .defaultMessageRetryDelay(Duration.ofMillis(settings.retryDelayMs()))
                .consumer(plainConsumer())
                .build();
        parallel = ParallelStreamProcessor.createEosStreamProcessor(options);
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
            task.nextAttempt();
            try {
                processor.process(task);
            } catch (Exception e) {
                tracer.trace("retry_scheduled", task, "error", e.toString());
                throw new IllegalStateException(e);
            }
            BoundaryGate.hit("business_commit", task.eventId());
        });
    }

    /** A plain KafkaConsumer: the library inspects its auto-commit setting reflectively. */
    private KafkaConsumer<String, String> plainConsumer() {
        Map<String, Object> config = new HashMap<>(consumerFactory.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
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
