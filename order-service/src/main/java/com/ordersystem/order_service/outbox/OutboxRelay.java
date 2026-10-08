package com.ordersystem.order_service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * PENDING outbox 레코드를 오래된 순으로 브로커에 발행한다. 발행 확인(ack)을
 * 받은 것만 SENT로 바꾸므로 브로커 장애 중에는 PENDING으로 남았다가 복구 후
 * 재발행된다. 재발행 = 중복 발행 가능(at-least-once)이므로 소비 측 멱등
 * 처리가 전제다. 단일 인스턴스 전제의 폴링 릴레이다.
 *
 * 한 배치를 모두 보낸 뒤 순서대로 확인하고, 처음 실패한 레코드 앞까지만 SENT로
 * 바꾼다. 레코드마다 ack를 기다리고 따로 커밋하면 초당 발행 수가 왕복 지연과
 * 커밋 지연에 묶인다. 멱등 producer가 partition 안의 순서를 지키므로 정상
 * 상황의 순서는 같고, 실패 뒤 재발행되는 레코드는 소비 측 순번 검사가 막는다.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.outbox-enabled", havingValue = "true")
public class OutboxRelay {

    private static final String ALLOWED_EVENT_PACKAGE = "com.ordersystem.common.events.";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final long retentionMs;
    private long lastPurge;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, Object> kafkaTemplate,
                       ObjectMapper objectMapper, @Value("${app.outbox-retention-ms:0}") long retentionMs) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retentionMs = retentionMs;
    }

    @Scheduled(fixedDelayString = "${app.outbox-poll-ms:100}")
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.findTop500ByStatusOrderByIdAsc(OutboxEvent.Status.PENDING);
        List<CompletableFuture<SendResult<String, Object>>> sends = new ArrayList<>(batch.size());
        try {
            for (OutboxEvent record : batch) {
                Object event = objectMapper.readValue(record.getPayload(), resolveEventType(record.getEventType()));
                sends.add(kafkaTemplate.send(record.getTopic(), record.getAggregateId(), event));
            }
        } catch (Exception e) {
            log.warn("Outbox relay could not prepare record {}: {}", batch.get(sends.size()).getId(), e.toString());
        }
        List<Long> sent = new ArrayList<>(sends.size());
        for (int i = 0; i < sends.size(); i++) {
            OutboxEvent record = batch.get(i);
            try {
                sends.get(i).get(30, TimeUnit.SECONDS);
                com.ordersystem.common.experiment.BoundaryGate.hit("broker_ack", record.getEventId());
                sent.add(record.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 발행 순서를 지키기 위해 실패 지점에서 멈추고 다음 주기에 그 레코드부터 재시도한다
                log.warn("Outbox relay stopped at record {} (event {}): {}",
                        record.getId(), record.getEventId(), e.toString());
                break;
            }
        }
        if (!sent.isEmpty()) outboxEventRepository.markSent(sent, LocalDateTime.now());
        purgeSent();
    }

    /** 보존 기간이 지난 SENT 레코드를 지운다. 0이면 지우지 않는다. */
    private void purgeSent() {
        long now = System.currentTimeMillis();
        if (retentionMs <= 0 || now - lastPurge < 1000) return;
        lastPurge = now;
        outboxEventRepository.deleteSentBefore(LocalDateTime.now().minusNanos(retentionMs * 1_000_000));
    }

    private Class<?> resolveEventType(String eventType) throws ClassNotFoundException {
        if (!eventType.startsWith(ALLOWED_EVENT_PACKAGE)) {
            throw new IllegalStateException("Unexpected outbox event type: " + eventType);
        }
        return Class.forName(eventType);
    }
}
