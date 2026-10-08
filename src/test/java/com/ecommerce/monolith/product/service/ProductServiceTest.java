package com.ecommerce.monolith.product.service;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.product.dto.ProductRequest;
import com.ecommerce.monolith.product.dto.ProductResponse;
import com.ecommerce.monolith.product.entity.Product;
import com.ecommerce.monolith.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void updateProductRequestDoesNotExposeStockQuantity() {
        boolean hasStockQuantity = Arrays.stream(ProductRequest.Update.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("stockQuantity"));

        assertThat(hasStockQuantity).isFalse();
    }

    @Test
    void updateProductDoesNotChangeStockQuantity() {
        Product product = Product.builder()
                .productId(1L)
                .name("Phone")
                .description("Old description")
                .price(BigDecimal.valueOf(1000))
                .stockQuantity(3)
                .status(Product.ProductStatus.ACTIVE)
                .build();
        ProductRequest.Update request = new ProductRequest.Update();
        request.setName("Updated Phone");
        request.setPrice(BigDecimal.valueOf(1200));

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);

        ProductResponse.ProductInfo result = productService.updateProduct(1L, request);

        assertThat(result.getName()).isEqualTo("Updated Phone");
        assertThat(result.getPrice()).isEqualByComparingTo("1200");
        assertThat(result.getStockQuantity()).isEqualTo(3);
        verify(productRepository).save(product);
    }

    @Test
    void updateStockDecreasesStockWithConditionalUpdate() {
        Product product = Product.builder()
                .productId(1L)
                .name("Phone")
                .price(BigDecimal.valueOf(1000))
                .stockQuantity(3)
                .status(Product.ProductStatus.ACTIVE)
                .build();
        ProductRequest.StockUpdate request = new ProductRequest.StockUpdate();
        request.setOperation(ProductRequest.Operation.DECREASE);
        request.setQuantity(2);

        when(productRepository.decreaseStockIfAvailable(1L, 2)).thenReturn(1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse.StockInfo result = productService.updateStock(1L, request);

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getStockQuantity()).isEqualTo(3);
    }

    @Test
    void updateStockFailsWhenConditionalDecreaseAffectsNoRows() {
        ProductRequest.StockUpdate request = new ProductRequest.StockUpdate();
        request.setOperation(ProductRequest.Operation.DECREASE);
        request.setQuantity(10);

        when(productRepository.decreaseStockIfAvailable(1L, 10)).thenReturn(0);
        when(productRepository.existsById(1L)).thenReturn(true);

        assertThatThrownBy(() -> productService.updateStock(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_STOCK);
    }
}
