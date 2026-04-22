package com.shopease.order.controller;

import com.shopease.order.dto.*;
import com.shopease.order.service.OrderService;
import com.shopease.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(Authentication authentication,
                                                HttpServletRequest request,
                                                @Valid @RequestBody PlaceOrderRequest orderRequest) {
        String email = authentication.getPrincipal().toString();
        String token = extractToken(request);
        OrderDto order = orderService.placeOrder(email, token, orderRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping
    public ResponseEntity<List<OrderDto>> getOrders(Authentication authentication) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(orderService.getOrders(email));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrderById(Authentication authentication, @PathVariable Long id) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(orderService.getOrderById(email, id));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<OrderDto> cancelOrder(Authentication authentication, @PathVariable Long id) {
        String email = authentication.getPrincipal().toString();
        return ResponseEntity.ok(orderService.cancelOrder(email, id));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse> health() {
        return ResponseEntity.ok(new ApiResponse(true, "Order Service is running"));
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
