package com.shopease.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddToCartRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "Product name is required")
    private String productName;

    private String imageUrl;

    @NotNull(message = "Price is required")
    private Double price;

    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity = 1;
}
