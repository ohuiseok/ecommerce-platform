package com.ecommerce.monolith.common;

import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
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
                // 음수 총액은 환불액 상한도 위반하므로 DB의 제약 검사 순서에 의존하지 않는다.
                .satisfies(error -> assertThat(
                        error.getMessage().contains("ck_orders_total_amount_non_negative")
                                || error.getMessage().contains("ck_orders_refunded_amount_range"))
                        .as("총액 음수 또는 환불액 범위 CHECK 제약 위반")
                        .isTrue());

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
    void orderRefundAmountDefaultsToZeroAndStaysWithinTotalAmount() {
        Long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (user_id, total_amount, original_amount, discount_amount)
                VALUES (1, 25000, 25000, 0) RETURNING order_id
                """, Long.class);
        try {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT refunded_amount FROM orders WHERE order_id = ?", BigDecimal.class, orderId))
                    .isEqualByComparingTo(BigDecimal.ZERO);

            for (BigDecimal invalid : new BigDecimal[]{new BigDecimal("-0.01"), new BigDecimal("25000.01")}) {
                assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE orders SET refunded_amount = ? WHERE order_id = ?", invalid, orderId))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("ck_orders_refunded_amount_range");
            }
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE orders SET refunded_amount = NULL WHERE order_id = ?", orderId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbcTemplate.update("UPDATE orders SET refunded_amount = total_amount WHERE order_id = ?", orderId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT total_amount - refunded_amount FROM orders WHERE order_id = ?", BigDecimal.class, orderId))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            jdbcTemplate.update("DELETE FROM orders WHERE order_id = ?", orderId);
        }
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
