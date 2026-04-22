package com.shopease.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto {
    private Long id;
    private String userEmail;
    private String status;
    private List<OrderItemDto> items;
    private Double totalPrice;
    private int totalItems;

    // Shipping
    private String shippingName;
    private String shippingAddress;
    private String shippingCity;
    private String shippingState;
    private String shippingZip;
    private String shippingPhone;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
