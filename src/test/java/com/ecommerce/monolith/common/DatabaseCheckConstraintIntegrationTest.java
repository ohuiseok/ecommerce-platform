package com.ecommerce.monolith.common;

import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCheckConstraintIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void productStockQuantityCannotBeNegative() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO products (
                    name, price, stock_quantity, review_count
                ) VALUES (?, ?, ?, ?)
                """, "negative stock product", BigDecimal.valueOf(1000), -1, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_products_stock_quantity_non_negative");
    }

    @Test
    void orderAmountsCannotBeNegative() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO orders (
                    user_id, total_amount, original_amount, discount_amount
                ) VALUES (?, ?, ?, ?)
                """, 1L, BigDecimal.valueOf(-1), BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_total_amount_non_negative");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO orders (
                    user_id, total_amount, original_amount, discount_amount
                ) VALUES (?, ?, ?, ?)
                """, 1L, BigDecimal.ZERO, BigDecimal.valueOf(-1), BigDecimal.ZERO))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_original_amount_non_negative");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO orders (
                    user_id, total_amount, original_amount, discount_amount
                ) VALUES (?, ?, ?, ?)
                """, 1L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(-1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_discount_amount_non_negative");
    }

    @Test
    void paymentAmountCannotBeNegative() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO payments (
                    order_id, user_id, amount, method
                ) VALUES (?, ?, ?, ?)
                """, 1L, 1L, BigDecimal.valueOf(-1), "CARD"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_payments_amount_non_negative");
    }
}
