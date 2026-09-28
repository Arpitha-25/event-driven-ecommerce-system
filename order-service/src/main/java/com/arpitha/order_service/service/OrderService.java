package com.arpitha.order_service.service;

import com.arpitha.order_service.dto.CreateOrderRequest;
import com.arpitha.order_service.dto.OrderResponse;
import com.arpitha.order_service.dto.UpdateOrderRequest;

public interface OrderService {

    OrderResponse createOrder(CreateOrderRequest request);

    OrderResponse getOrderById(Long id);

    OrderResponse updateOrder(Long id, UpdateOrderRequest request);

    void deleteOrder(Long id);

    /** Saga step: inventory reserved the stock, so a CREATED order becomes CONFIRMED. */
    void confirmOrder(Long orderId);

    /** Saga step: inventory could not reserve the stock, so a CREATED order becomes REJECTED. */
    void rejectOrder(Long orderId, String reason);
}
