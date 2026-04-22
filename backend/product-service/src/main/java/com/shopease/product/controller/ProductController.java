package com.shopease.product.controller;

import com.shopease.common.dto.ApiResponse;
import com.shopease.product.dto.CreateProductRequest;
import com.shopease.product.dto.ProductDto;
import com.shopease.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ResponseEntity<List<ProductDto>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<List<ProductDto>> getProductsByCategory(@PathVariable Long categoryId) {
        return ResponseEntity.ok(productService.getProductsByCategory(categoryId));
    }

    @GetMapping("/search")
    public ResponseEntity<List<ProductDto>> searchProducts(@RequestParam String keyword) {
        return ResponseEntity.ok(productService.searchProducts(keyword));
    }

    @GetMapping("/price-range")
    public ResponseEntity<List<ProductDto>> getProductsByPriceRange(
            @RequestParam BigDecimal min, @RequestParam BigDecimal max) {
        return ResponseEntity.ok(productService.getProductsByPriceRange(min, max));
    }

    @PostMapping
    public ResponseEntity<ProductDto> createProduct(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductDto> updateProduct(@PathVariable Long id,
                                                     @Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(new ApiResponse(true, "Product deleted successfully"));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse> health() {
        return ResponseEntity.ok(new ApiResponse(true, "Product Service is running"));
    }
}
