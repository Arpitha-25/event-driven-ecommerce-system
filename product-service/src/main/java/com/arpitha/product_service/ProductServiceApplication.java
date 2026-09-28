package com.arpitha.product_service;

import com.arpitha.common.util.TimeZoneNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.arpitha"})
public class ProductServiceApplication {

    public static void main(String[] args) {
        TimeZoneNormalizer.normalizeDefault();
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
