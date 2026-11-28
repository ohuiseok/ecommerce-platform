package com.ecommerce.monolith.order.dto;

import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.entity.OrderItem;
import com.ecommerce.monolith.order.entity.ShippingAddress;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class OrderResponse {

    @Data
    @Builder
    public static class OrderInfo {
        private Long orderId;
        private Long userId;
        private BigDecimal originalAmount;
        private BigDecimal discountAmount;
        private BigDecimal totalAmount;
        private Long userCouponId;
        private Order.OrderStatus status;
        private ShippingAddressInfo shippingAddress;
        private List<OrderItemInfo> orderItems;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static OrderInfo from(Order order) {
            return OrderInfo.builder()
                    .orderId(order.getOrderId())
                    .userId(order.getUserId())
                    .originalAmount(order.getOriginalAmount())
                    .discountAmount(order.getDiscountAmount())
                    .totalAmount(order.getTotalAmount())
                    .userCouponId(order.getUserCouponId())
                    .status(order.getStatus())
                    .shippingAddress(ShippingAddressInfo.from(order.getShippingAddress()))
                    .orderItems(order.getOrderItems().stream()
                            .map(OrderItemInfo::from)
                            .collect(Collectors.toList()))
                    .createdAt(order.getCreatedAt())
                    .updatedAt(order.getUpdatedAt())
                    .build();
        }
    }

    @Data
    @Builder
    public static class OrderItemInfo {
        private Long orderItemId;
        private Long productId;
        private String productName;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal subtotal;
        private OrderItem.OrderItemStatus status;
        private BigDecimal lineGrossAmount;
        private BigDecimal lineCouponDiscountAmount;
        private BigDecimal linePaymentAmount;

        public static OrderItemInfo from(OrderItem orderItem) {
            return OrderItemInfo.builder()
                    .orderItemId(orderItem.getOrderItemId())
                    .productId(orderItem.getProductId())
                    .productName(orderItem.getProductName())
                    .price(orderItem.getPrice())
                    .quantity(orderItem.getQuantity())
                    .subtotal(orderItem.getSubtotal())
                    .status(orderItem.getStatus())
                    .lineGrossAmount(orderItem.getLineGrossAmount())
                    .lineCouponDiscountAmount(orderItem.getLineCouponDiscountAmount())
                    .linePaymentAmount(orderItem.getLinePaymentAmount())
                    .build();
        }
    }

    @Data
    @Builder
    public static class ShippingAddressInfo {
        private String zipCode;
        private String address;
        private String detailAddress;
        private String recipientName;
        private String recipientPhone;

        public static ShippingAddressInfo from(ShippingAddress shippingAddress) {
            if (shippingAddress == null) {
                return null;
            }
            return ShippingAddressInfo.builder()
                    .zipCode(shippingAddress.getZipCode())
                    .address(shippingAddress.getAddress())
                    .detailAddress(shippingAddress.getDetailAddress())
                    .recipientName(shippingAddress.getRecipientName())
                    .recipientPhone(shippingAddress.getRecipientPhone())
                    .build();
        }
    }
}
