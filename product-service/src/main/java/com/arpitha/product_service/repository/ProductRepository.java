package com.arpitha.product_service.repository;

import com.arpitha.product_service.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsBySku(String sku);
    
    boolean existsByName(String name);
    
    Optional<Product> findBySku(String sku);
}
