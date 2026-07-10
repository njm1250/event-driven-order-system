package com.ordersystem.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.common.events.OrderCreatedEvent;
import com.ordersystem.common.events.Topics;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.entity.OrderStatus;
import com.ordersystem.order_service.outbox.OutboxEvent;
import com.ordersystem.order_service.outbox.OutboxEventRepository;
import com.ordersystem.order_service.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UncheckedIOException;
import java.util.Optional;

@Service
public class OrderService {

    private final OrderProducer orderProducer;
    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final boolean outboxEnabled;

    public OrderService(OrderProducer orderProducer,
                        OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        @Value("${app.outbox-enabled:false}") boolean outboxEnabled) {
        this.orderProducer = orderProducer;
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxEnabled = outboxEnabled;
    }

    public Order createOrder(String productCode, int quantity, double price) {
        Order order = Order.builder()
                .productCode(productCode)
                .quantity(quantity)
                .price(price)
                .orderStatus(OrderStatus.PENDING)
                .build();

        if (outboxEnabled) {
            return createWithOutbox(order);
        }
        return createWithDirectPublish(order);
    }

    /**
     * 주문 저장과 outbox 기록을 한 트랜잭션으로 묶는다.
     * 커밋 이후의 브로커 발행은 OutboxRelay가 책임진다.
     */
    private Order createWithOutbox(Order order) {
        return transactionTemplate.execute(status -> {
            Order savedOrder = orderRepository.save(order);
            OrderCreatedEvent event = buildEvent(savedOrder);
            outboxEventRepository.save(OutboxEvent.builder()
                    .eventId(event.getEventId())
                    .aggregateId(String.valueOf(savedOrder.getOrderId()))
                    .topic(Topics.ORDER_CREATED)
                    .eventType(OrderCreatedEvent.class.getName())
                    .payload(serialize(event))
                    .build());
            return savedOrder;
        });
    }

    /**
     * 원래 구조: 저장 커밋 후 fire-and-forget 발행. DB 커밋과 발행 사이에서
     * 프로세스가 죽으면 이벤트가 유실된다(dual-write).
     */
    private Order createWithDirectPublish(Order order) {
        Order savedOrder = orderRepository.save(order);
        OrderCreatedEvent event = buildEvent(savedOrder);
        orderProducer.sendMessage(Topics.ORDER_CREATED, String.valueOf(savedOrder.getOrderId()), event);
        return savedOrder;
    }

    private OrderCreatedEvent buildEvent(Order order) {
        return OrderCreatedEvent.builder()
                .orderId(order.getOrderId())
                .productCode(order.getProductCode())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .build();
    }

    private String serialize(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Order> findOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }
}
