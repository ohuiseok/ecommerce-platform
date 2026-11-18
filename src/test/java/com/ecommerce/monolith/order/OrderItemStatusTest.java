package com.ecommerce.monolith.order;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.order.entity.OrderItem;
import com.ecommerce.monolith.order.entity.OrderItem.OrderItemStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.ecommerce.monolith.order.entity.OrderItem.OrderItemStatus.*;
import static org.assertj.core.api.Assertions.*;

class OrderItemStatusTest {

    @Test
    void newLinesStartOrdered() {
        assertThat(new OrderItem().getStatus()).isEqualTo(ORDERED);
        assertThat(OrderItem.builder().build().getStatus()).isEqualTo(ORDERED);
    }

    @Test
    void everyTransitionFollowsAdr013IncludingRetriesAndTerminalStates() {
        Map<OrderItemStatus, Set<OrderItemStatus>> allowed = Map.ofEntries(
                Map.entry(ORDERED, Set.of(CONFIRMED, CANCELLED)),
                Map.entry(CONFIRMED, Set.of(PREPARING, CANCELLED)),
                Map.entry(PREPARING, Set.of(SHIPPED, CANCELLED)),
                Map.entry(SHIPPED, Set.of(DELIVERED)),
                Map.entry(DELIVERED, Set.of(RETURN_REQUESTED, REFUND_REQUESTED)),
                Map.entry(RETURN_REQUESTED, Set.of(RETURNED, RETURN_REJECTED)),
                Map.entry(RETURNED, Set.of(REFUND_REQUESTED)),
                Map.entry(REFUND_REQUESTED, Set.of(REFUNDED, REFUND_FAILED)),
                Map.entry(REFUND_FAILED, Set.of(REFUND_REQUESTED)));
        for (OrderItemStatus from : OrderItemStatus.values()) {
            for (OrderItemStatus to : OrderItemStatus.values()) {
                OrderItem item = OrderItem.builder().status(from).build();
                if (allowed.getOrDefault(from, Set.of()).contains(to)) {
                    assertThat(item.canTransitionTo(to)).isTrue();
                    item.updateStatus(to);
                    assertThat(item.getStatus()).isEqualTo(to);
                } else {
                    assertThat(item.canTransitionTo(to)).isFalse();
                    assertThatThrownBy(() -> item.updateStatus(to)).isInstanceOf(BusinessException.class);
                    assertThat(item.getStatus()).isEqualTo(from);
                }
            }
        }
    }

    @Test
    void nullTransitionIsRejectedWithoutChangingState() {
        OrderItem item = new OrderItem();
        assertThat(item.canTransitionTo(null)).isFalse();
        assertThatThrownBy(() -> item.updateStatus(null)).isInstanceOf(BusinessException.class);
        assertThat(item.getStatus()).isEqualTo(ORDERED);
    }
}
