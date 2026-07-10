package com.ordersystem.order_service.config;

import com.ordersystem.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    private final int PARTITIONS;

    public KafkaTopicConfig(@Value("${app.kafka.partitions:3}") int partitions) {
        this.PARTITIONS = partitions;
    }

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(Topics.ORDER_CREATED).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic stockUpdatedTopic() {
        return TopicBuilder.name(Topics.STOCK_UPDATED).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic stockUpdateFailedTopic() {
        return TopicBuilder.name(Topics.STOCK_UPDATE_FAILED).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic stockUpdatedDltTopic() {
        return TopicBuilder.name(Topics.STOCK_UPDATED + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic stockUpdateFailedDltTopic() {
        return TopicBuilder.name(Topics.STOCK_UPDATE_FAILED + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }
}
