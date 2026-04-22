package com.shopease.product.service;

import com.shopease.common.exception.ResourceNotFoundException;
import com.shopease.product.dto.CreateProductRequest;
import com.shopease.product.dto.ProductDto;
import com.shopease.product.model.Category;
import com.shopease.product.model.Product;
import com.shopease.product.repository.CategoryRepository;
import com.shopease.product.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public List<ProductDto> getAllProducts() {
        return productRepository.findByActiveTrue().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public ProductDto getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return toDto(product);
    }

    public List<ProductDto> getProductsByCategory(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category not found with id: " + categoryId);
        }
        return productRepository.findByCategoryIdAndActiveTrue(categoryId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<ProductDto> searchProducts(String keyword) {
        return productRepository.searchProducts(keyword).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<ProductDto> getProductsByPriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        return productRepository.findByPriceRange(minPrice, maxPrice).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public ProductDto createProduct(CreateProductRequest request) {
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        Product product = new Product();
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setImageUrl(request.getImageUrl());
        product.setStock(request.getStock());
        product.setActive(true);
        product.setCategory(category);

        return toDto(productRepository.save(product));
    }

    public ProductDto updateProduct(Long id, CreateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setImageUrl(request.getImageUrl());
        product.setStock(request.getStock());
        product.setCategory(category);

        return toDto(productRepository.save(product));
    }

    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        product.setActive(false);
        productRepository.save(product);
    }

    private ProductDto toDto(Product product) {
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStock(),
                product.getActive(),
                product.getCategory().getId(),
                product.getCategory().getName()
        );
    }
}
