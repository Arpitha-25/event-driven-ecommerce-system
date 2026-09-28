package com.arpitha.inventory_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.arpitha.inventory_service.dto.CreateInventoryRequest;
import com.arpitha.inventory_service.dto.InventoryResponse;
import com.arpitha.inventory_service.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.arpitha.common.util.ValidationHelper;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@Import(ValidationHelper.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InventoryService inventoryService;

    private CreateInventoryRequest createRequest;
    private InventoryResponse response;
    private UUID id;
    private UUID productId;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        productId = UUID.randomUUID();

        createRequest = new CreateInventoryRequest();
        createRequest.setProductId(productId);
        createRequest.setSku("SKU-123");
        createRequest.setTotalQuantity(100);

        response = new InventoryResponse();
        response.setId(id);
        response.setProductId(productId);
        response.setSku("SKU-123");
    }

    @Test
    void createInventory_Success() throws Exception {
        when(inventoryService.createInventory(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void createInventory_ValidationError() throws Exception {
        createRequest.setSku(""); // Invalid

        mockMvc.perform(post("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getInventoryById_Success() throws Exception {
        when(inventoryService.getInventoryById(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/inventory/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
