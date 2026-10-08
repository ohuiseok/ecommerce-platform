package com.ecommerce.monolith.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

public class ProductRequest {

    @Data
    public static class Create {
        @NotBlank(message = "상품명은 필수입니다")
        private String name;

        private String description;

        @NotNull(message = "가격은 필수입니다")
        @DecimalMin(value = "0.0", inclusive = false, message = "가격은 0보다 커야 합니다")
        private BigDecimal price;

        @NotNull(message = "재고 수량은 필수입니다")
        @Min(value = 0, message = "재고 수량은 0 이상이어야 합니다")
        private Integer stockQuantity;

        private String category;
        private String brand;
        private String imageUrl;
    }

    @Data
    public static class Update {
        private String name;
        private String description;
        private BigDecimal price;
        private String category;
        private String brand;
        private String imageUrl;
    }

    @Data
    public static class StockUpdate {
        @NotNull(message = "수량은 필수입니다")
        @Min(value = 1, message = "수량은 1 이상이어야 합니다")
        private Integer quantity;

        @NotNull(message = "작업 타입은 필수입니다")
        private Operation operation;
    }

    public enum Operation {
        INCREASE, DECREASE
    }
}
