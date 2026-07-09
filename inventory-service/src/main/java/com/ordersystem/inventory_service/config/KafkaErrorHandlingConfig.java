package com.ordersystem.inventory_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 처리 불가능한 메시지 하나가 재시도를 반복하며 파티션의 후속 메시지 전체를
 * 막지 않도록, 1초 간격 3회 재시도 후 <topic>.DLT로 격리한다.
 * 역직렬화 실패는 ErrorHandlingDeserializer(application.properties)가 잡아
 * 이 핸들러로 넘긴다.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
