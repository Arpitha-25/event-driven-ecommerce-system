package com.arpitha.identity_service;

import com.arpitha.common.util.TimeZoneNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"com.arpitha"})
@ConfigurationPropertiesScan
public class IdentityServiceApplication {

    public static void main(String[] args) {
        TimeZoneNormalizer.normalizeDefault();
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
