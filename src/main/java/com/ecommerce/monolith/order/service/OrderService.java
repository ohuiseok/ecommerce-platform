package com.ecommerce.monolith.order.service;

import com.ecommerce.monolith.cart.dto.CartResponse;
import com.ecommerce.monolith.cart.service.CartService;
import com.ecommerce.monolith.coupon.service.CouponService;
import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.order.dto.OrderRequest;
import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.entity.OrderItem;
import com.ecommerce.monolith.order.entity.ShippingAddress;
import com.ecommerce.monolith.order.repository.OrderRepository;
import com.ecommerce.monolith.outbox.service.OutboxEventService;
import com.ecommerce.monolith.product.dto.ProductRequest;
import com.ecommerce.monolith.product.dto.ProductResponse;
import com.ecommerce.monolith.product.service.ProductService;
import com.ecommerce.monolith.user.dto.UserResponse;
import com.ecommerce.monolith.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserService userService;
    private final ProductService productService;
    private final CartService cartService;
    private final CouponService couponService;
    private final OutboxEventService outboxEventService;

    public OrderResponse.OrderInfo createOrder(Long userId, OrderRequest.Create request) {
        // 1. 사용자 정보 확인
        UserResponse.UserInfo userInfo = userService.getUserById(userId);

        // 2. 주문 생성
        Order order = Order.builder()
                .userId(userInfo.getUserId())
                .totalAmount(BigDecimal.ZERO)
                .shippingAddress(createShippingAddress(request.getShippingAddress()))
                .build();

        // 3. 주문 항목 처리
        for (OrderRequest.OrderItemRequest itemRequest : request.getOrderItems()) {
            addOrderItem(order, itemRequest.getProductId(), itemRequest.getQuantity());
        }

        // 4. 금액 계산 및 쿠폰 적용
        order.calculateAmounts();
        applyCouponIfPresent(order, userId, request.getUserCouponId());

        // 5. 주문 저장
        Order savedOrder = orderRepository.save(order);
        markCouponUsedIfPresent(request.getUserCouponId(), savedOrder.getOrderId());
        outboxEventService.recordOrderCreated(savedOrder);

        log.info("event=order.created orderId={} userId={} totalAmount={}",
                savedOrder.getOrderId(), savedOrder.getUserId(), savedOrder.getTotalAmount());

        return OrderResponse.OrderInfo.from(savedOrder);
    }

    public OrderResponse.OrderInfo createOrderFromCart(Long userId, OrderRequest.Checkout request) {
        UserResponse.UserInfo userInfo = userService.getUserById(userId);
        CartResponse.CartInfo cart = cartService.getCart(userId);

        if (cart.getItems().isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        Order order = Order.builder()
                .userId(userInfo.getUserId())
                .totalAmount(BigDecimal.ZERO)
                .shippingAddress(createShippingAddress(request.getShippingAddress()))
                .build();

        for (CartResponse.CartItemInfo item : cart.getItems()) {
            addOrderItem(order, item.getProductId(), item.getQuantity());
        }

        order.calculateAmounts();
        applyCouponIfPresent(order, userId, request.getUserCouponId());

        Order savedOrder = orderRepository.save(order);
        markCouponUsedIfPresent(request.getUserCouponId(), savedOrder.getOrderId());
        cartService.clearCart(userId);
        outboxEventService.recordOrderCreated(savedOrder);

        log.info("event=order.created_from_cart orderId={} userId={} totalAmount={}",
                savedOrder.getOrderId(), savedOrder.getUserId(), savedOrder.getTotalAmount());

        return OrderResponse.OrderInfo.from(savedOrder);
    }

    private void applyCouponIfPresent(Order order, Long userId, Long userCouponId) {
        if (userCouponId == null) {
            return;
        }

        BigDecimal discount = couponService.calculateDiscount(userId, userCouponId, order.getOriginalAmount());
        order.applyDiscount(discount, userCouponId);
    }

    private void markCouponUsedIfPresent(Long userCouponId, Long orderId) {
        if (userCouponId != null) {
            couponService.markUsed(userCouponId, orderId);
        }
    }

    private void addOrderItem(Order order, Long productId, Integer quantity) {
        ProductResponse.ProductInfo productInfo = productService.getProduct(productId);

        // 재고 확인 및 차감
        decreaseStock(productId, quantity);

        OrderItem orderItem = OrderItem.builder()
                .productId(productId)
                .productName(productInfo.getName())
                .price(productInfo.getPrice())
                .quantity(quantity)
                .build();

        order.addOrderItem(orderItem);
    }

    private ProductResponse.StockInfo decreaseStock(Long productId, Integer quantity) {
        ProductRequest.StockUpdate request = new ProductRequest.StockUpdate();
        request.setQuantity(quantity);
        request.setOperation(ProductRequest.Operation.DECREASE);

        return productService.updateStock(productId, request);
    }

    private ShippingAddress createShippingAddress(OrderRequest.ShippingAddressRequest request) {
        return ShippingAddress.builder()
                .zipCode(request.getZipCode())
                .address(request.getAddress())
                .detailAddress(request.getDetailAddress())
                .recipientName(request.getRecipientName())
                .recipientPhone(request.getRecipientPhone())
                .build();
    }

    @Transactional(readOnly = true)
    public OrderResponse.OrderInfo getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        return OrderResponse.OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse.OrderInfo> getOrdersByUserId(Long userId, Pageable pageable) {
        Page<Order> orders = orderRepository.findByUserId(userId, pageable);
        return orders.map(OrderResponse.OrderInfo::from);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse.OrderInfo> getOrdersByStatus(Order.OrderStatus status, Pageable pageable) {
        Page<Order> orders = orderRepository.findByStatus(status, pageable);
        return orders.map(OrderResponse.OrderInfo::from);
    }

    public OrderResponse.OrderInfo updateOrderStatus(Long orderId, OrderRequest.StatusUpdate request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        Order.OrderStatus newStatus;
        try {
            newStatus = Order.OrderStatus.valueOf(request.getStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS, "유효하지 않은 주문 상태입니다: " + request.getStatus());
        }

        order.updateStatus(newStatus);
        Order updatedOrder = orderRepository.save(order);

        log.info("event=order.status_updated orderId={} status={}", orderId, newStatus);

        return OrderResponse.OrderInfo.from(updatedOrder);
    }

    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.cancelByUser();
        orderRepository.save(order);

        restoreOrderResources(order);
        outboxEventService.recordOrderCancelled(order, "USER_CANCELLED");

        log.info("event=order.cancelled orderId={} userId={}", orderId, order.getUserId());
    }

    public void cancelPendingOrderAfterPaymentFailure(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == Order.OrderStatus.CANCELLED) {
            log.info("event=order.payment_failure_recovery_skipped orderId={} userId={} status={}",
                    orderId, order.getUserId(), order.getStatus());
            return;
        }

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            log.warn("event=order.payment_failure_recovery_skipped orderId={} userId={} status={}",
                    orderId, order.getUserId(), order.getStatus());
            return;
        }

        order.updateStatus(Order.OrderStatus.CANCELLED);
        orderRepository.save(order);
        restoreOrderResources(order);
        outboxEventService.recordOrderCancelled(order, "PAYMENT_FAILED");

        log.info("event=order.payment_failure_recovered orderId={} userId={}", orderId, order.getUserId());
    }

    public int expirePendingOrders(LocalDateTime cutoff, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        List<Order> expiredOrders = orderRepository.findByStatusAndCreatedAtLessThanEqual(
                Order.OrderStatus.PENDING,
                cutoff,
                PageRequest.of(0, batchSize)
        );

        int expiredCount = 0;
        for (Order order : expiredOrders) {
            if (expirePendingOrder(order)) {
                expiredCount++;
            }
        }

        return expiredCount;
    }

    private boolean expirePendingOrder(Order order) {
        if (order.getStatus() != Order.OrderStatus.PENDING) {
            log.info("event=order.expiration_skipped orderId={} userId={} status={}",
                    order.getOrderId(), order.getUserId(), order.getStatus());
            return false;
        }

        order.updateStatus(Order.OrderStatus.CANCELLED);
        orderRepository.save(order);
        restoreOrderResources(order);
        outboxEventService.recordOrderCancelled(order, "PENDING_EXPIRED");

        log.info("event=order.expired orderId={} userId={}", order.getOrderId(), order.getUserId());
        return true;
    }

    private void restoreOrderResources(Order order) {
        // 재고 복원
        for (OrderItem orderItem : order.getOrderItems()) {
            ProductRequest.StockUpdate request = new ProductRequest.StockUpdate();
            request.setQuantity(orderItem.getQuantity());
            request.setOperation(ProductRequest.Operation.INCREASE);

            productService.updateStock(orderItem.getProductId(), request);
        }

        // 쿠폰 복원
        if (order.getUserCouponId() != null) {
            couponService.restoreCoupon(order.getUserCouponId());
        }
    }

    @Transactional
    public void markOrderConfirmed(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.updateStatus(Order.OrderStatus.CONFIRMED);
        orderRepository.save(order);

        log.info("event=order.confirmed orderId={} userId={}", orderId, order.getUserId());
    }
}
