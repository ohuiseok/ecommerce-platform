package com.ecommerce.monolith.order;

import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.entity.OrderItem;
import com.ecommerce.monolith.order.entity.ShippingAddress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void orderResponseExposesStoredLineStateAndAmounts() {
        OrderItem item = OrderItem.builder()
                .price(new BigDecimal("9999")).quantity(3)
                .status(OrderItem.OrderItemStatus.CANCELLED)
                .lineGrossAmount(new BigDecimal("2000"))
                .lineCouponDiscountAmount(new BigDecimal("301"))
                .linePaymentAmount(new BigDecimal("1699"))
                .build();

        JsonNode line = responseLine(item);

        assertThat(line.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(line.get("lineGrossAmount").decimalValue()).isEqualByComparingTo("2000");
        assertThat(line.get("lineCouponDiscountAmount").decimalValue()).isEqualByComparingTo("301");
        assertThat(line.get("linePaymentAmount").decimalValue()).isEqualByComparingTo("1699");
        assertThat(line.get("subtotal").decimalValue()).isEqualByComparingTo("29997");
    }

    @Test
    void legacyLineKeepsUnknownSnapshotsNull() {
        JsonNode line = responseLine(OrderItem.builder()
                .price(new BigDecimal("1000")).quantity(2).build());

        assertThat(line.get("status").asText()).isEqualTo("ORDERED");
        for (String field : List.of("lineGrossAmount", "lineCouponDiscountAmount", "linePaymentAmount")) {
            assertThat(line.has(field)).isTrue();
            assertThat(line.get(field).isNull()).isTrue();
        }
        assertThat(line.get("subtotal").decimalValue()).isEqualByComparingTo("2000");
    }

    private JsonNode responseLine(OrderItem item) {
        Order order = Order.builder()
                .shippingAddress(new ShippingAddress())
                .orderItems(List.of(item)).build();
        return mapper.valueToTree(OrderResponse.OrderInfo.from(order)).get("orderItems").get(0);
    }
}
