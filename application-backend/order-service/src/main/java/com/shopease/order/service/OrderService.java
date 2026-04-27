package com.shopease.order.service;

import com.shopease.order.dto.*;
import com.shopease.order.model.Order;
import com.shopease.order.model.OrderItem;
import com.shopease.order.model.OrderStatus;
import com.shopease.order.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;
    private final String cartServiceUrl;

    public OrderService(OrderRepository orderRepository,
                        @Value("${app.cart-service.url}") String cartServiceUrl) {
        this.orderRepository = orderRepository;
        this.restTemplate = new RestTemplate();
        this.cartServiceUrl = cartServiceUrl;
    }

    @Transactional
    public OrderDto placeOrder(String userEmail, String token, PlaceOrderRequest request) {
        // 1. Fetch cart from cart-service
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> cartResponse = restTemplate.exchange(
                cartServiceUrl + "/api/cart", HttpMethod.GET, entity, Map.class);

        Map<String, Object> cart = cartResponse.getBody();
        if (cart == null) {
            throw new RuntimeException("Failed to fetch cart");
        }

        List<Map<String, Object>> cartItems = (List<Map<String, Object>>) cart.get("items");
        if (cartItems == null || cartItems.isEmpty()) {
            throw new RuntimeException("Cart is empty. Add items before placing an order.");
        }

        // 2. Create order
        Order order = new Order();
        order.setUserEmail(userEmail);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setShippingName(request.getShippingName());
        order.setShippingAddress(request.getShippingAddress());
        order.setShippingCity(request.getShippingCity());
        order.setShippingState(request.getShippingState());
        order.setShippingZip(request.getShippingZip());
        order.setShippingPhone(request.getShippingPhone());

        double totalPrice = 0;
        int totalItems = 0;

        for (Map<String, Object> item : cartItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(((Number) item.get("productId")).longValue());
            orderItem.setProductName((String) item.get("productName"));
            orderItem.setImageUrl((String) item.get("imageUrl"));
            orderItem.setPrice(((Number) item.get("price")).doubleValue());
            orderItem.setQuantity(((Number) item.get("quantity")).intValue());
            orderItem.setSubtotal(((Number) item.get("subtotal")).doubleValue());

            order.getItems().add(orderItem);
            totalPrice += orderItem.getSubtotal();
            totalItems += orderItem.getQuantity();
        }

        order.setTotalPrice(totalPrice);
        order.setTotalItems(totalItems);
        order = orderRepository.save(order);

        // 3. Clear cart after placing order
        try {
            restTemplate.exchange(
                    cartServiceUrl + "/api/cart", HttpMethod.DELETE, entity, Map.class);
        } catch (Exception e) {
            // Log but don't fail the order
            System.err.println("Warning: Failed to clear cart after order: " + e.getMessage());
        }

        return toDto(order);
    }

    public List<OrderDto> getOrders(String userEmail) {
        return orderRepository.findByUserEmailOrderByCreatedAtDesc(userEmail)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public OrderDto getOrderById(String userEmail, Long orderId) {
        Order order = orderRepository.findByIdAndUserEmail(orderId, userEmail)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        return toDto(order);
    }

    @Transactional
    public OrderDto cancelOrder(String userEmail, Long orderId) {
        Order order = orderRepository.findByIdAndUserEmail(orderId, userEmail)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED) {
            throw new RuntimeException("Cannot cancel order that is already " + order.getStatus().name().toLowerCase());
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("Order is already cancelled");
        }

        order.setStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        return toDto(order);
    }

    private OrderDto toDto(Order order) {
        OrderDto dto = new OrderDto();
        dto.setId(order.getId());
        dto.setUserEmail(order.getUserEmail());
        dto.setStatus(order.getStatus().name());
        dto.setTotalPrice(order.getTotalPrice());
        dto.setTotalItems(order.getTotalItems());
        dto.setShippingName(order.getShippingName());
        dto.setShippingAddress(order.getShippingAddress());
        dto.setShippingCity(order.getShippingCity());
        dto.setShippingState(order.getShippingState());
        dto.setShippingZip(order.getShippingZip());
        dto.setShippingPhone(order.getShippingPhone());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());
        dto.setItems(order.getItems().stream().map(item -> {
            OrderItemDto itemDto = new OrderItemDto();
            itemDto.setId(item.getId());
            itemDto.setProductId(item.getProductId());
            itemDto.setProductName(item.getProductName());
            itemDto.setImageUrl(item.getImageUrl());
            itemDto.setPrice(item.getPrice());
            itemDto.setQuantity(item.getQuantity());
            itemDto.setSubtotal(item.getSubtotal());
            return itemDto;
        }).collect(Collectors.toList()));
        return dto;
    }
}
