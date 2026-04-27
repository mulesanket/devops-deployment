package com.shopease.product.controller;

import com.shopease.product.dto.CategoryDto;
import com.shopease.product.service.CategoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<List<CategoryDto>> getAllCategories() {
        return ResponseEntity.ok(categoryService.getAllCategories());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryDto> getCategoryById(@PathVariable Long id) {
        return ResponseEntity.ok(categoryService.getCategoryById(id));
    }

    @PostMapping
    public ResponseEntity<CategoryDto> createCategory(@RequestParam String name,
                                                       @RequestParam(required = false) String description,
                                                       @RequestParam(required = false) String imageUrl) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categoryService.createCategory(name, description, imageUrl));
    }
}
