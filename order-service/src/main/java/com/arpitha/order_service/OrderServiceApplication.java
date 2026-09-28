package com.arpitha.order_service;

import com.arpitha.common.util.TimeZoneNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication(scanBasePackages = {"com.arpitha"})
public class OrderServiceApplication {

	public static void main(String[] args) {
		TimeZoneNormalizer.normalizeDefault();
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}