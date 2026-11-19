package com.ecommerce.monolith.order.entity;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "order_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer quantity;

    // 할인 배분 및 주문 생성 연동 전의 기존 라인은 스냅샷이 없으므로 null을 유지한다.
    @Column(precision = 19, scale = 2)
    private BigDecimal lineGrossAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal lineCouponDiscountAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal linePaymentAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    @lombok.Setter(lombok.AccessLevel.NONE)
    private OrderItemStatus status = OrderItemStatus.ORDERED;

    public enum OrderItemStatus {
        ORDERED, CONFIRMED, PREPARING, SHIPPED, DELIVERED, CANCELLED,
        RETURN_REQUESTED, RETURN_REJECTED, RETURNED,
        REFUND_REQUESTED, REFUND_FAILED, REFUNDED
    }

    private static final Map<OrderItemStatus, Set<OrderItemStatus>> ALLOWED_STATUS_TRANSITIONS = Map.ofEntries(
            Map.entry(OrderItemStatus.ORDERED, Set.of(OrderItemStatus.CONFIRMED, OrderItemStatus.CANCELLED)),
            Map.entry(OrderItemStatus.CONFIRMED, Set.of(OrderItemStatus.PREPARING, OrderItemStatus.CANCELLED)),
            Map.entry(OrderItemStatus.PREPARING, Set.of(OrderItemStatus.SHIPPED, OrderItemStatus.CANCELLED)),
            Map.entry(OrderItemStatus.SHIPPED, Set.of(OrderItemStatus.DELIVERED)),
            Map.entry(OrderItemStatus.DELIVERED, Set.of(OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.REFUND_REQUESTED)),
            Map.entry(OrderItemStatus.RETURN_REQUESTED, Set.of(OrderItemStatus.RETURNED, OrderItemStatus.RETURN_REJECTED)),
            Map.entry(OrderItemStatus.RETURNED, Set.of(OrderItemStatus.REFUND_REQUESTED)),
            Map.entry(OrderItemStatus.REFUND_REQUESTED, Set.of(OrderItemStatus.REFUNDED, OrderItemStatus.REFUND_FAILED)),
            Map.entry(OrderItemStatus.REFUND_FAILED, Set.of(OrderItemStatus.REFUND_REQUESTED))
    );

    public boolean canTransitionTo(OrderItemStatus nextStatus) {
        return nextStatus != null && status != null
                && ALLOWED_STATUS_TRANSITIONS.getOrDefault(status, Set.of()).contains(nextStatus);
    }

    public void updateStatus(OrderItemStatus nextStatus) {
        if (!canTransitionTo(nextStatus)) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS,
                    "허용되지 않은 주문 라인 상태 전이입니다: " + status + " -> " + nextStatus);
        }
        this.status = nextStatus;
    }

    public BigDecimal getSubtotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
