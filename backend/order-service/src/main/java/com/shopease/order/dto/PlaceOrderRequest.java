package com.shopease.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PlaceOrderRequest {

    @NotBlank(message = "Full name is required")
    private String shippingName;

    @NotBlank(message = "Address is required")
    private String shippingAddress;

    @NotBlank(message = "City is required")
    private String shippingCity;

    @NotBlank(message = "State is required")
    private String shippingState;

    @NotBlank(message = "ZIP code is required")
    private String shippingZip;

    @NotBlank(message = "Phone number is required")
    private String shippingPhone;
}
