package com.ordersystem.order_service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PENDING outbox 레코드를 오래된 순으로 브로커에 발행한다. 발행 확인(ack)을
 * 받은 것만 SENT로 바꾸므로 브로커 장애 중에는 PENDING으로 남았다가 복구 후
 * 재발행된다. 재발행 = 중복 발행 가능(at-least-once)이므로 소비 측 멱등
 * 처리가 전제다. 단일 인스턴스 전제의 폴링 릴레이다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox-enabled", havingValue = "true")
public class OutboxRelay {

    private static final String ALLOWED_EVENT_PACKAGE = "com.ordersystem.common.events.";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.findTop100ByStatusOrderByIdAsc(OutboxEvent.Status.PENDING);

        for (OutboxEvent record : batch) {
            try {
                Object event = objectMapper.readValue(record.getPayload(), resolveEventType(record.getEventType()));
                kafkaTemplate.send(record.getTopic(), record.getAggregateId(), event)
                        .get(5, TimeUnit.SECONDS);
                record.markSent();
                outboxEventRepository.save(record);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 발행 순서를 지키기 위해 실패 지점에서 멈추고 다음 주기에 처음부터 재시도한다
                log.warn("Outbox relay stopped at record {} (event {}): {}",
                        record.getId(), record.getEventId(), e.toString());
                return;
            }
        }
    }

    private Class<?> resolveEventType(String eventType) throws ClassNotFoundException {
        if (!eventType.startsWith(ALLOWED_EVENT_PACKAGE)) {
            throw new IllegalStateException("Unexpected outbox event type: " + eventType);
        }
        return Class.forName(eventType);
    }
}
