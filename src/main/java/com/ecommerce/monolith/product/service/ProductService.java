package com.ecommerce.monolith.product.service;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.product.dto.ProductRequest;
import com.ecommerce.monolith.product.dto.ProductResponse;
import com.ecommerce.monolith.product.entity.Product;
import com.ecommerce.monolith.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    public ProductResponse.ProductInfo createProduct(ProductRequest.Create request) {
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .stockQuantity(request.getStockQuantity())
                .category(request.getCategory())
                .brand(request.getBrand())
                .imageUrl(request.getImageUrl())
                .build();

        Product savedProduct = productRepository.save(product);
        log.info("Product created successfully: {}", savedProduct.getName());

        return ProductResponse.ProductInfo.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public ProductResponse.ProductInfo getProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        return ProductResponse.ProductInfo.from(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse.ProductInfo> getProducts(Pageable pageable) {
        Page<Product> products = productRepository.findByStatus(Product.ProductStatus.ACTIVE, pageable);
        return products.map(ProductResponse.ProductInfo::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse.ProductInfo> searchProducts(String keyword, Pageable pageable) {
        Page<Product> products = productRepository.searchByKeywordAndStatus(
                keyword, Product.ProductStatus.ACTIVE, pageable);
        return products.map(ProductResponse.ProductInfo::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse.ProductInfo> getProductsByCategory(String category, Pageable pageable) {
        Page<Product> products = productRepository.findByCategoryAndStatus(
                category, Product.ProductStatus.ACTIVE, pageable);
        return products.map(ProductResponse.ProductInfo::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse.ProductInfo> getProductsByBrand(String brand, Pageable pageable) {
        Page<Product> products = productRepository.findByBrandIgnoreCaseAndStatus(
                brand, Product.ProductStatus.ACTIVE, pageable);
        return products.map(ProductResponse.ProductInfo::from);
    }

    @Transactional(readOnly = true)
    public List<String> getAllCategories() {
        return productRepository.findAllCategoriesByStatus(Product.ProductStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<String> getAllBrands() {
        return productRepository.findAllBrandsByStatus(Product.ProductStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse.ProductInfo> getLowStockProducts(Integer threshold) {
        List<Product> products = productRepository.findLowStockProducts(
                threshold != null ? threshold : 10, Product.ProductStatus.ACTIVE);
        return products.stream()
                .map(ProductResponse.ProductInfo::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse.ProductInfo> getProductsByPriceRange(
            BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        Page<Product> products = productRepository.findByPriceRangeAndStatus(
                minPrice, maxPrice, Product.ProductStatus.ACTIVE, pageable);
        return products.map(ProductResponse.ProductInfo::from);
    }

    public ProductResponse.ProductInfo updateProduct(Long productId, ProductRequest.Update request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (request.getName() != null) {
            product.setName(request.getName());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (request.getCategory() != null) {
            product.setCategory(request.getCategory());
        }
        if (request.getBrand() != null) {
            product.setBrand(request.getBrand());
        }
        if (request.getImageUrl() != null) {
            product.setImageUrl(request.getImageUrl());
        }

        Product updatedProduct = productRepository.save(product);
        log.info("Product updated successfully: {}", updatedProduct.getName());

        return ProductResponse.ProductInfo.from(updatedProduct);
    }

    public ProductResponse.StockInfo updateStock(Long productId, ProductRequest.StockUpdate request) {
        int updatedRows = switch (request.getOperation()) {
            case INCREASE -> productRepository.increaseStock(productId, request.getQuantity());
            case DECREASE -> productRepository.decreaseStockIfAvailable(productId, request.getQuantity());
        };

        if (updatedRows == 0) {
            if (!productRepository.existsById(productId)) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }

        Product updatedProduct = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        log.info("Product stock updated: {} {} {}", 
                updatedProduct.getName(), request.getOperation(), request.getQuantity());

        return ProductResponse.StockInfo.from(updatedProduct);
    }

    @Transactional(readOnly = true)
    public ProductResponse.StockInfo checkStock(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        return ProductResponse.StockInfo.from(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse.StockInfo> checkMultipleStocks(List<Long> productIds) {
        List<Product> products = productRepository.findByProductIdIn(productIds);
        return products.stream()
                .map(ProductResponse.StockInfo::from)
                .collect(Collectors.toList());
    }

    public void updateRatingStats(Long productId, BigDecimal averageRating, int reviewCount) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        product.updateRatingStats(averageRating, reviewCount);
        productRepository.save(product);
    }

    public void deleteProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        product.setStatus(Product.ProductStatus.INACTIVE);
        productRepository.save(product);
        log.info("Product deleted successfully: {}", product.getName());
    }
}
