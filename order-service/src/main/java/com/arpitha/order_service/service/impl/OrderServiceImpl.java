package com.arpitha.order_service.service.impl;

import com.arpitha.order_service.dto.CreateOrderRequest;
import com.arpitha.order_service.dto.OrderResponse;
import com.arpitha.order_service.dto.UpdateOrderRequest;
import com.arpitha.order_service.entity.Order;
import com.arpitha.order_service.entity.OrderStatus;
import com.arpitha.order_service.event.mapper.OrderEventMapper;
import com.arpitha.order_service.event.publisher.OrderEventPublisher;
import com.arpitha.order_service.exception.OrderNotFoundException;
import com.arpitha.order_service.mapper.OrderMapper;
import com.arpitha.order_service.repository.OrderRepository;
import com.arpitha.order_service.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderEventMapper orderEventMapper;
    private final OrderMapper orderMapper;

    public OrderServiceImpl(OrderRepository orderRepository,
                            OrderEventPublisher orderEventPublisher,
                            OrderEventMapper orderEventMapper,
                            OrderMapper orderMapper) {

        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.orderEventMapper = orderEventMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    public OrderResponse createOrder(CreateOrderRequest request) {

        Order order = orderMapper.toEntity(request);
        order.setStatus(OrderStatus.CREATED);

        Order savedOrder = orderRepository.save(order);

        orderEventPublisher.publishOrderCreatedEvent(
                orderEventMapper.toCreatedEvent(savedOrder)
        );

        return orderMapper.toResponse(savedOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = getOrderEntityById(id);
        return orderMapper.toResponse(order);
    }

    @Override
    public OrderResponse updateOrder(Long id, UpdateOrderRequest request) {

        Order existingOrder = getOrderEntityById(id);

        orderMapper.updateEntityFromRequest(request, existingOrder);

        if (request.getStatus() != null) {
            existingOrder.setStatus(OrderStatus.valueOf(request.getStatus()));
        }

        Order savedOrder = orderRepository.save(existingOrder);

        orderEventPublisher.publishOrderUpdatedEvent(
                orderEventMapper.toUpdatedEvent(savedOrder)
        );

        return orderMapper.toResponse(savedOrder);
    }

    @Override
    public void deleteOrder(Long id) {

        Order order = getOrderEntityById(id);
        
        order.setStatus(OrderStatus.CANCELLED);
        Order savedOrder = orderRepository.save(order);

        orderEventPublisher.publishOrderCancelledEvent(
                orderEventMapper.toCancelledEvent(savedOrder)
        );
    }

    @Override
    public void confirmOrder(Long orderId) {

        Order order = getOrderEntityById(orderId);

        // Events can be redelivered, and the order may have been cancelled meanwhile:
        // only a CREATED order moves forward, anything else is left as it is.
        if (order.getStatus() != OrderStatus.CREATED) {
            log.info("Ignoring inventory reservation for order {} in status {}", orderId, order.getStatus());
            return;
        }

        order.setStatus(OrderStatus.CONFIRMED);
        order.setStatusReason(null);
        orderRepository.save(order);
        log.info("Order {} confirmed: stock reserved", orderId);
    }

    @Override
    public void rejectOrder(Long orderId, String reason) {

        Order order = getOrderEntityById(orderId);

        if (order.getStatus() != OrderStatus.CREATED) {
            log.info("Ignoring reservation failure for order {} in status {}", orderId, order.getStatus());
            return;
        }

        order.setStatus(OrderStatus.REJECTED);
        order.setStatusReason(reason);
        orderRepository.save(order);
        log.info("Order {} rejected: {}", orderId, reason);
    }

    private Order getOrderEntityById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() ->
                        new OrderNotFoundException(
                                "Order not found with ID: " + id));
    }
}

