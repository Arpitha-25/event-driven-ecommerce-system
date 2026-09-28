package com.arpitha.order_service.repository;

import com.arpitha.order_service.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, Long> {
}