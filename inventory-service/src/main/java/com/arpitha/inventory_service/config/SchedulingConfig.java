package com.arpitha.inventory_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the hourly cleanup of old processed-event records. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
