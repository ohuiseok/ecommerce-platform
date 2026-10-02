package com.ecommerce.monolith.order.entity;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal originalAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** 이 주문에 적용된 UserCoupon ID. 쿠폰을 사용하지 않았다면 null. */
    private Long userCouponId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Embedded
    private ShippingAddress shippingAddress;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> orderItems = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum OrderStatus {
        PENDING,        // 주문 생성됨
        CONFIRMED,      // 주문 확인됨
        PROCESSING,     // 처리 중
        SHIPPED,        // 배송 중
        DELIVERED,      // 배송 완료
        CANCELLED       // 주문 취소
    }

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_STATUS_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED_STATUS_TRANSITIONS.put(OrderStatus.PENDING, EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED));
        ALLOWED_STATUS_TRANSITIONS.put(OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED));
        ALLOWED_STATUS_TRANSITIONS.put(OrderStatus.PROCESSING, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
        ALLOWED_STATUS_TRANSITIONS.put(OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED));
        ALLOWED_STATUS_TRANSITIONS.put(OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED_STATUS_TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    public void addOrderItem(OrderItem orderItem) {
        orderItems.add(orderItem);
        orderItem.setOrder(this);
    }

    public void updateStatus(OrderStatus status) {
        if (!canTransitionTo(status)) {
            throw new BusinessException(
                    ErrorCode.INVALID_ORDER_STATUS,
                    "허용되지 않은 주문 상태 전이입니다: " + this.status + " -> " + status
            );
        }
        this.status = status;
    }

    public boolean canTransitionTo(OrderStatus nextStatus) {
        if (nextStatus == null) {
            return false;
        }
        return ALLOWED_STATUS_TRANSITIONS
                .getOrDefault(this.status, Set.of())
                .contains(nextStatus);
    }

    public void cancelByUser() {
        if (this.status != OrderStatus.PENDING && this.status != OrderStatus.CONFIRMED) {
            throw new BusinessException(
                    ErrorCode.ORDER_CANCELLATION_NOT_ALLOWED,
                    "사용자 취소는 결제 대기 또는 결제 확정 상태에서만 가능합니다"
            );
        }
        updateStatus(OrderStatus.CANCELLED);
    }

    public void calculateAmounts() {
        this.originalAmount = orderItems.stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.totalAmount = this.originalAmount.subtract(this.discountAmount);
    }

    public void applyDiscount(BigDecimal discountAmount, Long userCouponId) {
        this.discountAmount = discountAmount;
        this.userCouponId = userCouponId;
        this.totalAmount = this.originalAmount.subtract(discountAmount);
    }
}
