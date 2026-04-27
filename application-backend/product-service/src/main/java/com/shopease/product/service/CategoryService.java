package com.shopease.product.service;

import com.shopease.common.exception.ResourceAlreadyExistsException;
import com.shopease.common.exception.ResourceNotFoundException;
import com.shopease.product.dto.CategoryDto;
import com.shopease.product.model.Category;
import com.shopease.product.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<CategoryDto> getAllCategories() {
        return categoryRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public CategoryDto getCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
        return toDto(category);
    }

    public CategoryDto createCategory(String name, String description, String imageUrl) {
        if (categoryRepository.existsByName(name)) {
            throw new ResourceAlreadyExistsException("Category already exists: " + name);
        }
        Category category = new Category();
        category.setName(name);
        category.setDescription(description);
        category.setImageUrl(imageUrl);
        return toDto(categoryRepository.save(category));
    }

    private CategoryDto toDto(Category category) {
        int productCount = category.getProducts() != null ? category.getProducts().size() : 0;
        return new CategoryDto(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getImageUrl(),
                productCount
        );
    }
}
