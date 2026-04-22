package com.shopease.cart.controller;

import com.shopease.cart.dto.*;
import com.shopease.cart.service.CartService;
import com.shopease.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartDto> getCart(Authentication authentication) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(cartService.getCart(email));
    }

    @PostMapping("/items")
    public ResponseEntity<CartDto> addToCart(Authentication authentication,
                                             @Valid @RequestBody AddToCartRequest request) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(cartService.addToCart(email, request));
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<CartDto> updateQuantity(Authentication authentication,
                                                   @PathVariable Long itemId,
                                                   @Valid @RequestBody UpdateQuantityRequest request) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(cartService.updateQuantity(email, itemId, request));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<CartDto> removeItem(Authentication authentication,
                                               @PathVariable Long itemId) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(cartService.removeItem(email, itemId));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse> clearCart(Authentication authentication) {
        String email = authentication.getPrincipal().toString();
        cartService.clearCart(email);
        return ResponseEntity.ok(new ApiResponse(true, "Cart cleared"));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse> health() {
        return ResponseEntity.ok(new ApiResponse(true, "Cart Service is running"));
    }
}
