package com.shopease.order.repository;

import com.shopease.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserEmailOrderByCreatedAtDesc(String userEmail);
    Optional<Order> findByIdAndUserEmail(Long id, String userEmail);
}
