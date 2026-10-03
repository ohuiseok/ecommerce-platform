package com.ecommerce.monolith.order;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.coupon.entity.Coupon;
import com.ecommerce.monolith.coupon.entity.UserCoupon;
import com.ecommerce.monolith.coupon.repository.CouponRepository;
import com.ecommerce.monolith.coupon.repository.UserCouponRepository;
import com.ecommerce.monolith.order.dto.OrderRequest;
import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.repository.OrderRepository;
import com.ecommerce.monolith.order.service.OrderService;
import com.ecommerce.monolith.product.entity.Product;
import com.ecommerce.monolith.product.repository.ProductRepository;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import com.ecommerce.monolith.user.entity.User;
import com.ecommerce.monolith.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCancellationExpirationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void userCancellationAndPendingExpirationRestoreResourcesOnlyOnce() throws InterruptedException {
        int initialStock = 5;
        int orderQuantity = 2;
        User user = userRepository.save(User.builder()
                .email("cancel-expire-buyer@example.com")
                .password("encoded-password")
                .name("Cancel Expire Buyer")
                .phoneNumber("010-5555-6666")
                .build());
        Product product = productRepository.save(Product.builder()
                .name("취소 만료 경쟁 상품")
                .price(BigDecimal.valueOf(20000))
                .stockQuantity(initialStock)
                .status(Product.ProductStatus.ACTIVE)
                .build());
        Coupon coupon = couponRepository.save(Coupon.builder()
                .code("CANCEL_EXPIRE_ONCE")
                .name("취소 만료 경쟁 쿠폰")
                .discountType(Coupon.DiscountType.FIXED_AMOUNT)
                .discountValue(BigDecimal.valueOf(1000))
                .minOrderAmount(BigDecimal.ZERO)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(1))
                .issueLimit(10)
                .issuedCount(1)
                .build());
        UserCoupon userCoupon = userCouponRepository.save(UserCoupon.builder()
                .userId(user.getUserId())
                .coupon(coupon)
                .issuedAt(LocalDateTime.now())
                .build());

        OrderResponse.OrderInfo createdOrder = orderService.createOrder(
                user.getUserId(),
                createOrderRequest(product.getProductId(), orderQuantity, userCoupon.getUserCouponId())
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger cancellationAttempts = new AtomicInteger();
        AtomicInteger expirationAttempts = new AtomicInteger();
        AtomicInteger stateConflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        CompletableFuture<Void> userCancellation = CompletableFuture.runAsync(() -> {
            readyLatch.countDown();
            await(startLatch);
            try {
                orderService.cancelOrder(createdOrder.getOrderId());
                cancellationAttempts.incrementAndGet();
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.ORDER_STATE_CONFLICT
                        || e.getErrorCode() == ErrorCode.ORDER_CANCELLATION_NOT_ALLOWED) {
                    stateConflicts.incrementAndGet();
                    return;
                }
                unexpectedFailure.compareAndSet(null, e);
            } catch (ObjectOptimisticLockingFailureException e) {
                stateConflicts.incrementAndGet();
            } catch (Exception e) {
                unexpectedFailure.compareAndSet(null, e);
            }
        }, executor);

        CompletableFuture<Void> pendingExpiration = CompletableFuture.runAsync(() -> {
            readyLatch.countDown();
            await(startLatch);
            try {
                expirationAttempts.addAndGet(orderService.expirePendingOrders(LocalDateTime.now().plusMinutes(1), 10));
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.ORDER_STATE_CONFLICT) {
                    stateConflicts.incrementAndGet();
                    return;
                }
                unexpectedFailure.compareAndSet(null, e);
            } catch (ObjectOptimisticLockingFailureException e) {
                stateConflicts.incrementAndGet();
            } catch (Exception e) {
                unexpectedFailure.compareAndSet(null, e);
            }
        }, executor);

        readyLatch.await();
        startLatch.countDown();
        CompletableFuture.allOf(userCancellation, pendingExpiration).join();
        executor.shutdownNow();

        Order finalOrder = orderRepository.findById(createdOrder.getOrderId()).orElseThrow();
        Product finalProduct = productRepository.findById(product.getProductId()).orElseThrow();
        UserCoupon finalUserCoupon = userCouponRepository.findById(userCoupon.getUserCouponId()).orElseThrow();

        assertThat(unexpectedFailure.get()).isNull();
        assertThat(cancellationAttempts.get() + expirationAttempts.get() + stateConflicts.get()).isGreaterThanOrEqualTo(1);
        assertThat(finalOrder.getStatus()).isEqualTo(Order.OrderStatus.CANCELLED);
        assertThat(finalProduct.getStockQuantity()).isEqualTo(initialStock);
        assertThat(finalProduct.getStatus()).isEqualTo(Product.ProductStatus.ACTIVE);
        assertThat(finalUserCoupon.getStatus()).isEqualTo(UserCoupon.CouponStatus.ISSUED);
        assertThat(finalUserCoupon.getOrderId()).isNull();
    }

    private static OrderRequest.Create createOrderRequest(Long productId, int quantity, Long userCouponId) {
        OrderRequest.OrderItemRequest item = new OrderRequest.OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);

        OrderRequest.Create request = new OrderRequest.Create();
        request.setOrderItems(List.of(item));
        request.setShippingAddress(shippingAddressRequest());
        request.setUserCouponId(userCouponId);
        return request;
    }

    private static OrderRequest.ShippingAddressRequest shippingAddressRequest() {
        OrderRequest.ShippingAddressRequest request = new OrderRequest.ShippingAddressRequest();
        request.setZipCode("12345");
        request.setAddress("Seoul");
        request.setRecipientName("Cancel Expire Buyer");
        request.setRecipientPhone("010-5555-6666");
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
