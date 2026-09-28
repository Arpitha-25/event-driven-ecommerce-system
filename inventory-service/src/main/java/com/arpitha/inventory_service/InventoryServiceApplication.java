package com.arpitha.inventory_service;

import com.arpitha.common.util.TimeZoneNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.arpitha"})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        TimeZoneNormalizer.normalizeDefault();
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
