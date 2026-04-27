package com.shopease.cart.service;

import com.shopease.cart.dto.*;
import com.shopease.cart.model.Cart;
import com.shopease.cart.model.CartItem;
import com.shopease.cart.repository.CartRepository;
import com.shopease.cart.repository.CartItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    public CartService(CartRepository cartRepository, CartItemRepository cartItemRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
    }

    public CartDto getCart(String userEmail) {
        Cart cart = cartRepository.findByUserEmail(userEmail)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setUserEmail(userEmail);
                    return cartRepository.save(newCart);
                });
        return toDto(cart);
    }

    @Transactional
    public CartDto addToCart(String userEmail, AddToCartRequest request) {
        Cart cart = cartRepository.findByUserEmail(userEmail)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setUserEmail(userEmail);
                    return cartRepository.save(newCart);
                });

        // Check if product already in cart
        CartItem existing = cart.getItems().stream()
                .filter(item -> item.getProductId().equals(request.getProductId()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + request.getQuantity());
        } else {
            CartItem item = new CartItem();
            item.setCart(cart);
            item.setProductId(request.getProductId());
            item.setProductName(request.getProductName());
            item.setImageUrl(request.getImageUrl());
            item.setPrice(request.getPrice());
            item.setQuantity(request.getQuantity());
            cart.getItems().add(item);
        }

        cart = cartRepository.save(cart);
        return toDto(cart);
    }

    @Transactional
    public CartDto updateQuantity(String userEmail, Long itemId, UpdateQuantityRequest request) {
        Cart cart = cartRepository.findByUserEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        CartItem item = cart.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Item not found in cart"));

        item.setQuantity(request.getQuantity());
        cart = cartRepository.save(cart);
        return toDto(cart);
    }

    @Transactional
    public CartDto removeItem(String userEmail, Long itemId) {
        Cart cart = cartRepository.findByUserEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Cart not found"));

        cart.getItems().removeIf(item -> item.getId().equals(itemId));
        cart = cartRepository.save(cart);
        return toDto(cart);
    }

    @Transactional
    public void clearCart(String userEmail) {
        cartRepository.findByUserEmail(userEmail).ifPresent(cart -> {
            cart.getItems().clear();
            cartRepository.save(cart);
        });
    }

    private CartDto toDto(Cart cart) {
        CartDto dto = new CartDto();
        dto.setId(cart.getId());
        dto.setUserEmail(cart.getUserEmail());
        dto.setItems(cart.getItems().stream().map(item -> {
            CartItemDto itemDto = new CartItemDto();
            itemDto.setId(item.getId());
            itemDto.setProductId(item.getProductId());
            itemDto.setProductName(item.getProductName());
            itemDto.setImageUrl(item.getImageUrl());
            itemDto.setPrice(item.getPrice());
            itemDto.setQuantity(item.getQuantity());
            itemDto.setSubtotal(item.getPrice() * item.getQuantity());
            return itemDto;
        }).collect(Collectors.toList()));
        dto.setTotalPrice(dto.getItems().stream().mapToDouble(CartItemDto::getSubtotal).sum());
        dto.setTotalItems(dto.getItems().stream().mapToInt(CartItemDto::getQuantity).sum());
        return dto;
    }
}
