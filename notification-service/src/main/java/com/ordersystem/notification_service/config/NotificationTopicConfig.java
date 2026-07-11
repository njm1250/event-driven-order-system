package com.ordersystem.notification_service.config;

import com.ordersystem.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class NotificationTopicConfig {

    @Bean
    NewTopic realtimeNotificationTopic(
            @Value("${app.notification.realtime-partitions:2}") int partitions) {
        return topic(Topics.NOTIFICATION_REALTIME, partitions);
    }

    @Bean
    NewTopic bulkNotificationTopic(
            @Value("${app.notification.bulk-partitions:10}") int partitions) {
        return topic(Topics.NOTIFICATION_BULK, partitions);
    }

    @Bean
    NewTopic realtimeNotificationDlt(
            @Value("${app.notification.realtime-partitions:2}") int partitions) {
        return topic(Topics.NOTIFICATION_REALTIME + ".DLT", partitions);
    }

    @Bean
    NewTopic bulkNotificationDlt(
            @Value("${app.notification.bulk-partitions:10}") int partitions) {
        return topic(Topics.NOTIFICATION_BULK + ".DLT", partitions);
    }

    private NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
