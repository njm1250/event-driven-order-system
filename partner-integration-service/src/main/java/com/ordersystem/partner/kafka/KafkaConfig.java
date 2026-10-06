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
            "retention.ms", "86400000", "retention.bytes", "67108864", "segment.bytes", "1048576");

    @Bean
    KafkaAdmin.NewTopics partnerTopics(PartnerSettings settings) {
        List<NewTopic> topics = new ArrayList<>();
        topics.add(new NewTopic(settings.topic(), settings.partitions(), (short) 1).configs(RETENTION));
        if (settings.mode() == ProcessingMode.RETRY_TOPIC) {
            topics.add(new NewTopic(settings.retryTopic(), 1, (short) 1).configs(RETENTION));
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
        // Parallel Consumer owns its own KafkaConsumer instead of a Spring listener container.
        factory.setAutoStartup(settings.mode() != ProcessingMode.PARALLEL_CONSUMER);
        factory.getContainerProperties().setAsyncAcks(settings.mode() == ProcessingMode.ASYNC);
        factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                pending.getObject().revokeAll();
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
    ConcurrentKafkaListenerContainerFactory<String, String> retryFactory(
            ConsumerFactory<String, String> consumerFactory, PartnerSettings settings) {
        var factory = baseFactory(consumerFactory);
        factory.setAutoStartup(settings.mode() == ProcessingMode.RETRY_TOPIC);
        return factory;
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
