package com.ordersystem.order_service.service;

import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class OrderService {

    @Autowired
    private OrderProducer orderProducer;

    @Autowired
    private OrderRepository orderRepository;

    public void createOrder(Order order) {
        orderRepository.save(order);

        // String message = "Order Created: " + order.getId();
        // orderProducer.sendMessage("inventory-topic", message);
    }
}
