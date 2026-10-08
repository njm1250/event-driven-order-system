package com.ordersystem.order_service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.PartnerCompletionEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;

/**
 * Closes delivery obligations from the partner integration's completion reports. Reports are
 * at-least-once; closing is idempotent per eventId, and the offset is committed after the update.
 */
@ConditionalOnProperty(name = "app.delivery-tracking", havingValue = "true")
@Configuration
public class CompletionListener {
    private final DeliveryObligations obligations;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public CompletionListener(DeliveryObligations obligations, TransactionTemplate tx, ObjectMapper json) {
        this.obligations = obligations;
        this.tx = tx;
        this.json = json;
    }

    @Bean
    static ConcurrentKafkaListenerContainerFactory<String, String> completionFactory(KafkaProperties properties) {
        var config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500, Long.MAX_VALUE)));
        return factory;
    }

    @KafkaListener(id = "delivery-completions", topics = "${app.completion-topic}", groupId = "${app.completion-group:source-completions}",
            containerFactory = "completionFactory")
    public void receive(List<ConsumerRecord<String, String>> records, Acknowledgment ack) throws Exception {
        List<PartnerCompletionEvent> events = new java.util.ArrayList<>(records.size());
        for (var record : records) events.add(json.readValue(record.value(), PartnerCompletionEvent.class));
        tx.executeWithoutResult(status -> events.forEach(e -> obligations.resolve(e.eventId(), e.sellerId(), e.completedAt())));
        ack.acknowledge();
    }
}
