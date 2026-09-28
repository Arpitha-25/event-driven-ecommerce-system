package com.arpitha.order_service.mapper;

import com.arpitha.order_service.dto.CreateOrderRequest;
import com.arpitha.order_service.dto.OrderResponse;
import com.arpitha.order_service.dto.OrderSummaryResponse;
import com.arpitha.order_service.dto.UpdateOrderRequest;
import com.arpitha.order_service.entity.Order;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "statusReason", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Order toEntity(CreateOrderRequest request);

    OrderResponse toResponse(Order order);

    OrderSummaryResponse toSummaryResponse(Order order);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "statusReason", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(UpdateOrderRequest request, @MappingTarget Order order);
}
