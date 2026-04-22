package com.shopease.cart.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartDto {
    private Long id;
    private String userEmail;
    private List<CartItemDto> items;
    private Double totalPrice;
    private int totalItems;
}
