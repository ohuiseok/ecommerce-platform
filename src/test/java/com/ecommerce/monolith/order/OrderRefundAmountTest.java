package com.ecommerce.monolith.order;

import com.ecommerce.monolith.order.entity.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRefundAmountTest {

    @Test
    void newOrdersStartWithNoRefunds() {
        Order order = Order.builder().totalAmount(new BigDecimal("25000.00")).build();

        assertThat(order.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(new Order().getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getRemainingPaymentAmount()).isEqualByComparingTo("25000.00");
        assertThat(order.getRefundableAmount(false)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getRefundableAmount(true)).isEqualByComparingTo("25000.00");
    }

    @Test
    void partialAndFullRefundsPreserveOriginalPaymentAmount() {
        Order order = Order.builder()
                .totalAmount(new BigDecimal("25000.00"))
                .refundedAmount(new BigDecimal("16666.00"))
                .status(Order.OrderStatus.CANCELLED)
                .build();

        assertThat(order.getRemainingPaymentAmount()).isEqualByComparingTo("8334.00");
        assertThat(order.getRefundableAmount(true)).isEqualByComparingTo("8334.00");
        assertThat(order.getRefundableAmount(false)).isEqualByComparingTo(BigDecimal.ZERO);

        order.setRefundedAmount(new BigDecimal("25000.00"));

        assertThat(order.getRemainingPaymentAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getRefundableAmount(true)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("25000.00");
    }
}
