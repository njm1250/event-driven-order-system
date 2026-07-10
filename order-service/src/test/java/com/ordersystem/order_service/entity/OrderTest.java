package com.ordersystem.order_service.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    private Order pendingOrder() {
        return Order.builder()
                .productCode("P001")
                .quantity(1)
                .price(1000)
                .orderStatus(OrderStatus.PENDING)
                .build();
    }

    @Test
    void PENDING_주문은_확정으로_전이된다() {
        Order order = pendingOrder();

        assertThat(order.confirm()).isTrue();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void PENDING_주문은_취소로_전이된다() {
        Order order = pendingOrder();

        assertThat(order.cancel()).isTrue();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 확정된_주문에_중복_확정_이벤트가_와도_상태가_바뀌지_않는다() {
        Order order = pendingOrder();
        order.confirm();

        assertThat(order.confirm()).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void 취소된_주문은_늦게_도착한_확정_이벤트로_되살아나지_않는다() {
        Order order = pendingOrder();
        order.cancel();

        assertThat(order.confirm()).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 확정된_주문은_늦게_도착한_취소_이벤트로_뒤집히지_않는다() {
        Order order = pendingOrder();
        order.confirm();

        assertThat(order.cancel()).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
