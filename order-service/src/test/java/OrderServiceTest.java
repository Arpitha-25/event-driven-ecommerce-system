package com.arpitha.order_service.service;

import com.arpitha.order_service.dto.CreateOrderRequest;
import com.arpitha.order_service.dto.OrderResponse;
import com.arpitha.order_service.entity.Order;
import com.arpitha.order_service.entity.OrderStatus;
import com.arpitha.order_service.event.mapper.OrderEventMapper;
import com.arpitha.order_service.event.model.OrderCancelledEvent;
import com.arpitha.order_service.event.model.OrderCreatedEvent;
import com.arpitha.order_service.event.publisher.OrderEventPublisher;
import com.arpitha.order_service.mapper.OrderMapper;
import com.arpitha.order_service.repository.OrderRepository;
import com.arpitha.order_service.service.impl.OrderServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private OrderEventMapper orderEventMapper;

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void createOrder_ShouldSaveOrderAndSendKafkaEvent() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductName("iPhone 16");
        request.setQuantity(2);
        request.setPrice(85000.0);

        Order order = Order.builder()
                .productName("iPhone 16")
                .quantity(2)
                .price(85000.0)
                .status(OrderStatus.CREATED)
                .build();

        OrderResponse response = new OrderResponse();
        response.setProductName("iPhone 16");
        
        OrderCreatedEvent event = new OrderCreatedEvent(1L, PRODUCT_ID, "iPhone 16", 2, 85000.0, "CREATED");

        when(orderMapper.toEntity(any(CreateOrderRequest.class))).thenReturn(order);
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderMapper.toResponse(any(Order.class))).thenReturn(response);
        when(orderEventMapper.toCreatedEvent(any(Order.class))).thenReturn(event);

        OrderResponse savedOrder = orderService.createOrder(request);

        assertNotNull(savedOrder);
        assertEquals("iPhone 16", savedOrder.getProductName());

        verify(orderRepository, times(1)).save(any(Order.class));
        verify(orderEventPublisher, times(1)).publishOrderCreatedEvent(any(OrderCreatedEvent.class));
    }

    @Test
    void getOrderById_ShouldReturnOrderResponse() {
        Order order = Order.builder()
                .id(1L)
                .productName("Laptop")
                .quantity(1)
                .price(50000.0)
                .status(OrderStatus.CREATED)
                .build();

        OrderResponse response = new OrderResponse();
        response.setProductName("Laptop");

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderMapper.toResponse(any(Order.class))).thenReturn(response);

        OrderResponse result = orderService.getOrderById(1L);

        assertNotNull(result);
        assertEquals("Laptop", result.getProductName());
    }

    @Test
    void deleteOrder_ShouldDeleteOrderAndSendKafkaEvent() {
        Order order = Order.builder()
                .id(1L)
                .productName("Phone")
                .quantity(1)
                .price(20000.0)
                .status(OrderStatus.CREATED)
                .build();
                
        OrderCancelledEvent event = new OrderCancelledEvent(1L, PRODUCT_ID, "Phone", 1, 20000.0, "CANCELLED");

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderEventMapper.toCancelledEvent(any(Order.class))).thenReturn(event);

        orderService.deleteOrder(1L);

        verify(orderRepository, times(1)).save(order);
        verify(orderEventPublisher, times(1)).publishOrderCancelledEvent(any(OrderCancelledEvent.class));
    }

    @Test
    void confirmOrder_ShouldMoveCreatedOrderToConfirmed() {
        Order order = Order.builder().id(1L).status(OrderStatus.CREATED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.confirmOrder(1L);

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void rejectOrder_ShouldMoveCreatedOrderToRejectedWithReason() {
        Order order = Order.builder().id(1L).status(OrderStatus.CREATED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.rejectOrder(1L, "Insufficient stock");

        assertEquals(OrderStatus.REJECTED, order.getStatus());
        assertEquals("Insufficient stock", order.getStatusReason());
        verify(orderRepository).save(order);
    }

    @Test
    void confirmOrder_ShouldIgnoreOrderThatIsNoLongerCreated() {
        Order order = Order.builder().id(1L).status(OrderStatus.CANCELLED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.confirmOrder(1L);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void rejectOrder_ShouldIgnoreDuplicateAfterConfirmation() {
        Order order = Order.builder().id(1L).status(OrderStatus.CONFIRMED).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.rejectOrder(1L, "late duplicate");

        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        verify(orderRepository, never()).save(any(Order.class));
    }
}
