package com.arpitha.inventory_service.service;

import com.arpitha.inventory_service.dto.CreateInventoryRequest;
import com.arpitha.inventory_service.dto.InventoryResponse;
import com.arpitha.inventory_service.dto.UpdateInventoryRequest;
import com.arpitha.inventory_service.entity.Inventory;
import com.arpitha.inventory_service.enums.InventoryStatus;
import com.arpitha.inventory_service.exception.DuplicateInventoryException;
import com.arpitha.inventory_service.exception.InvalidInventoryStateException;
import com.arpitha.inventory_service.exception.InventoryNotFoundException;
import com.arpitha.inventory_service.mapper.InventoryMapper;
import com.arpitha.inventory_service.repository.InventoryRepository;
import com.arpitha.inventory_service.service.impl.InventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryMapper inventoryMapper;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    private Inventory inventory;
    private CreateInventoryRequest createRequest;
    private UpdateInventoryRequest updateRequest;
    private UUID id;
    private UUID productId;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        productId = UUID.randomUUID();

        inventory = new Inventory();
        inventory.setId(id);
        inventory.setProductId(productId);
        inventory.setSku("SKU-123");
        inventory.setTotalQuantity(100);
        inventory.setReservedQuantity(0);
        inventory.setAvailableQuantity(100);
        inventory.setStatus(InventoryStatus.ACTIVE);

        createRequest = new CreateInventoryRequest();
        createRequest.setProductId(productId);
        createRequest.setSku("SKU-123");
        createRequest.setTotalQuantity(100);

        updateRequest = new UpdateInventoryRequest();
        updateRequest.setTotalQuantity(150);
    }

    @Test
    void createInventory_Success() {
        when(inventoryRepository.existsByProductId(productId)).thenReturn(false);
        when(inventoryRepository.existsBySku("SKU-123")).thenReturn(false);
        when(inventoryMapper.toEntity(createRequest)).thenReturn(inventory);
        when(inventoryRepository.save(any(Inventory.class))).thenReturn(inventory);
        when(inventoryMapper.toResponse(inventory)).thenReturn(new InventoryResponse());

        assertNotNull(inventoryService.createInventory(createRequest));
        verify(inventoryRepository).save(any(Inventory.class));
    }

    @Test
    void createInventory_DuplicateProductId() {
        when(inventoryRepository.existsByProductId(productId)).thenReturn(true);
        assertThrows(DuplicateInventoryException.class, () -> inventoryService.createInventory(createRequest));
    }

    @Test
    void updateInventory_Success() {
        when(inventoryRepository.findById(id)).thenReturn(Optional.of(inventory));
        doNothing().when(inventoryMapper).updateEntityFromRequest(updateRequest, inventory);
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(inventoryMapper.toResponse(inventory)).thenReturn(new InventoryResponse());

        assertNotNull(inventoryService.updateInventory(id, updateRequest));
    }

    @Test
    void updateInventory_InvalidState() {
        when(inventoryRepository.findById(id)).thenReturn(Optional.of(inventory));
        doAnswer(invocation -> {
            Inventory inv = invocation.getArgument(1);
            inv.setTotalQuantity(5);
            inv.setReservedQuantity(10); // Causes total < reserved
            return null;
        }).when(inventoryMapper).updateEntityFromRequest(updateRequest, inventory);

        assertThrows(InvalidInventoryStateException.class, () -> inventoryService.updateInventory(id, updateRequest));
    }

    @Test
    void deleteInventory_Success() {
        when(inventoryRepository.findById(id)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);

        inventoryService.deleteInventory(id);

        assertEquals(InventoryStatus.DISCONTINUED, inventory.getStatus());
        assertEquals(0, inventory.getAvailableQuantity());
        verify(inventoryRepository).save(inventory);
    }

    @Test
    void getInventoryById_NotFound() {
        when(inventoryRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(InventoryNotFoundException.class, () -> inventoryService.getInventoryById(id));
    }
}
