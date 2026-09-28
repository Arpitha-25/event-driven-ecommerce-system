package com.arpitha.order_service.controller;

import com.arpitha.common.dto.ApiResponse;
import com.arpitha.order_service.dto.CreateOrderRequest;
import com.arpitha.order_service.dto.OrderResponse;
import com.arpitha.order_service.dto.UpdateOrderRequest;
import com.arpitha.order_service.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order Controller", description = "Order Management API")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new order", description = "Creates a new order and publishes an OrderCreatedEvent")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Order created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input")
    })
    public ApiResponse<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ApiResponse.<OrderResponse>builder()
                .success(true)
                .message("Order created successfully")
                .data(response)
                .build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by ID", description = "Fetches order details by its ID")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order fetched successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ApiResponse<OrderResponse> getOrderById(@PathVariable Long id) {
        OrderResponse response = orderService.getOrderById(id);
        return ApiResponse.<OrderResponse>builder()
                .success(true)
                .message("Order fetched successfully")
                .data(response)
                .build();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an order", description = "Updates an existing order and publishes an OrderUpdatedEvent")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order updated successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ApiResponse<OrderResponse> updateOrder(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateOrderRequest request) {
        OrderResponse response = orderService.updateOrder(id, request);
        return ApiResponse.<OrderResponse>builder()
                .success(true)
                .message("Order updated successfully")
                .data(response)
                .build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an order", description = "Marks an order as cancelled and publishes an OrderCancelledEvent")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Order deleted successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ApiResponse<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ApiResponse.<Void>builder()
                .success(true)
                .message("Order deleted successfully")
                .build();
    }
}