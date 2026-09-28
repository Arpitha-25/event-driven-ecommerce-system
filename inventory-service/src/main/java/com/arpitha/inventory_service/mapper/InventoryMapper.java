package com.arpitha.inventory_service.mapper;

import com.arpitha.inventory_service.dto.CreateInventoryRequest;
import com.arpitha.inventory_service.dto.InventoryResponse;
import com.arpitha.inventory_service.dto.InventorySummaryResponse;
import com.arpitha.inventory_service.dto.UpdateInventoryRequest;
import com.arpitha.inventory_service.entity.Inventory;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface InventoryMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "reservedQuantity", ignore = true)
    @Mapping(target = "availableQuantity", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Inventory toEntity(CreateInventoryRequest request);

    InventoryResponse toResponse(Inventory inventory);

    InventorySummaryResponse toSummaryResponse(Inventory inventory);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "sku", ignore = true)
    @Mapping(target = "reservedQuantity", ignore = true)
    @Mapping(target = "availableQuantity", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromRequest(UpdateInventoryRequest request, @MappingTarget Inventory inventory);
}
