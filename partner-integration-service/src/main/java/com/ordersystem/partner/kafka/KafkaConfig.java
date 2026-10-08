package com.ordersystem.partner.kafka;

import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import com.ordersystem.partner.config.ProcessingMode;
import com.ordersystem.partner.dispatch.AsyncPendingQueue;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
public class KafkaConfig {
    private static final Map<String, String> RETENTION = Map.of(
            "retention.ms", "86400000", "retention.bytes", "2147483648", "segment.bytes", "67108864");

    @Bean
    KafkaAdmin.NewTopics partnerTopics(PartnerSettings settings) {
        List<NewTopic> topics = new ArrayList<>();
        topics.add(new NewTopic(settings.topic(), settings.partitions(), settings.replicationFactor()).configs(RETENTION));
        if (settings.mode().usesRetryTopic()) {
            topics.add(new NewTopic(settings.retryTopic(), retryPartitions(settings), settings.replicationFactor()).configs(RETENTION));
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> partnerFactory(
            ConsumerFactory<String, String> consumerFactory, PartnerSettings settings,
            ObjectProvider<AsyncPendingQueue> pending, ObjectProvider<Tracer> tracer) {
        var factory = baseFactory(consumerFactory);
        // The inbox has a single ingestion consumer because it is the authority for the capacity check.
        factory.setConcurrency(settings.mode() == ProcessingMode.INBOX ? 1 : settings.partitions());
        // Parallel Consumer owns its own KafkaConsumer; inbox v2 uses the batch listener instead.
        factory.setAutoStartup(!settings.mode().usesParallelConsumer()
                && !(settings.mode() == ProcessingMode.INBOX && settings.inboxBatchIngest()));
        factory.getContainerProperties().setAsyncAcks(settings.mode() == ProcessingMode.ASYNC);
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                // Each container shares this queue; only its own revoked partitions are dropped.
                if (settings.mode() == ProcessingMode.ASYNC) {
                    pending.getObject().revoke(partitions.stream().filter(p -> p.topic().equals(settings.topic()))
                            .map(TopicPartition::partition).toList());
                }
                tracer.getObject().trace("partitions_revoked", null, "partitions", partitions.toString());
            }

            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                tracer.getObject().trace("partitions_assigned", null, "partitions", partitions.toString());
            }
        });
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> inboxBatchFactory(
            ConsumerFactory<String, String> consumerFactory, PartnerSettings settings) {
        var factory = baseFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(1);
        factory.setAutoStartup(settings.mode() == ProcessingMode.INBOX && settings.inboxBatchIngest());
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> retryFactory(
            ConsumerFactory<String, String> consumerFactory, PartnerSettings settings) {
        var factory = baseFactory(consumerFactory);
        factory.setConcurrency(retryPartitions(settings));
        factory.setAutoStartup(settings.mode().usesRetryTopic());
        return factory;
    }

    /** The original retry-topic mode used one partition; the comparison candidate spreads the lane. */
    static int retryPartitions(PartnerSettings settings) {
        return settings.mode() == ProcessingMode.KAFKA_RETRY ? settings.retry().topicPartitions() : 1;
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> baseFactory(
            ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Never skip a record: a failed listener call is retried until it succeeds.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(100, Long.MAX_VALUE)));
        return factory;
    }
}
