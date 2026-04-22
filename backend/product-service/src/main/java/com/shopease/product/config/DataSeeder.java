package com.shopease.product.config;

import com.shopease.product.model.Category;
import com.shopease.product.model.Product;
import com.shopease.product.repository.CategoryRepository;
import com.shopease.product.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public DataSeeder(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (categoryRepository.count() > 0) {
            System.out.println("Data already seeded. Skipping...");
            return;
        }

        System.out.println("Seeding categories and products...");

        // Categories
        Category electronics = createCategory("Electronics", "Gadgets, devices & tech accessories");
        Category fashion = createCategory("Fashion", "Clothing, shoes & accessories");
        Category home = createCategory("Home & Living", "Decor, furniture & home essentials");
        Category beauty = createCategory("Beauty", "Skincare, makeup & fragrances");

        // Electronics products
        createProduct("Wireless Headphones", "Premium noise-cancelling wireless headphones with 30hr battery life",
                new BigDecimal("79.99"), "/images/products/wireless-headphones.jpg", 50, electronics);
        createProduct("Smart Watch", "Fitness tracking smartwatch with heart rate monitor and GPS",
                new BigDecimal("199.99"), "/images/products/smart-watch.jpg", 35, electronics);
        createProduct("Laptop Stand", "Ergonomic aluminum laptop stand for comfortable working",
                new BigDecimal("49.99"), "/images/products/laptop-stand.jpg", 80, electronics);
        createProduct("Bluetooth Speaker", "Portable waterproof bluetooth speaker with deep bass",
                new BigDecimal("39.99"), "/images/products/bluetooth-speaker.jpg", 60, electronics);

        // Fashion products
        createProduct("Leather Jacket", "Classic genuine leather jacket with modern slim fit",
                new BigDecimal("149.99"), "/images/products/leather-jacket.jpg", 25, fashion);
        createProduct("Running Shoes", "Lightweight breathable running shoes with cushioned sole",
                new BigDecimal("89.99"), "/images/products/running-shoes.jpg", 45, fashion);
        createProduct("Sunglasses", "Polarized UV protection sunglasses with metal frame",
                new BigDecimal("59.99"), "/images/products/sunglasses.jpg", 70, fashion);
        createProduct("Denim Jeans", "Slim fit stretch denim jeans in classic indigo wash",
                new BigDecimal("69.99"), "/images/products/denim-jeans.jpg", 55, fashion);

        // Home & Living products
        createProduct("Candle Set", "Luxury scented soy candle set with 3 fragrances",
                new BigDecimal("34.99"), "/images/products/candle-set.jpg", 40, home);
        createProduct("Throw Pillow", "Velvet decorative throw pillow with geometric pattern",
                new BigDecimal("24.99"), "/images/products/throw-pillow.jpg", 90, home);
        createProduct("Plant Pot", "Minimalist ceramic indoor plant pot with bamboo tray",
                new BigDecimal("19.99"), "/images/products/plant-pot.jpg", 65, home);
        createProduct("Wall Clock", "Modern silent wall clock with Scandinavian design",
                new BigDecimal("44.99"), "/images/products/wall-clock.jpg", 30, home);

        // Beauty products
        createProduct("Skincare Set", "Complete daily skincare routine set with cleanser, toner & moisturizer",
                new BigDecimal("64.99"), "/images/products/skincare-set.jpg", 40, beauty);
        createProduct("Perfume", "Luxury eau de parfum with floral and woody notes",
                new BigDecimal("89.99"), "/images/products/perfume.jpg", 35, beauty);
        createProduct("Makeup Palette", "Professional 18-shade eyeshadow palette with mirror",
                new BigDecimal("42.99"), "/images/products/makeup-palette.jpg", 50, beauty);
        createProduct("Face Serum", "Vitamin C brightening face serum with hyaluronic acid",
                new BigDecimal("29.99"), "/images/products/face-serum.jpg", 60, beauty);

        System.out.println("Data seeding completed! 4 categories, 16 products.");
    }

    private Category createCategory(String name, String description) {
        Category category = new Category();
        category.setName(name);
        category.setDescription(description);
        return categoryRepository.save(category);
    }

    private void createProduct(String name, String description, BigDecimal price,
                                String imageUrl, int stock, Category category) {
        Product product = new Product();
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setImageUrl(imageUrl);
        product.setStock(stock);
        product.setActive(true);
        product.setCategory(category);
        productRepository.save(product);
    }
}
