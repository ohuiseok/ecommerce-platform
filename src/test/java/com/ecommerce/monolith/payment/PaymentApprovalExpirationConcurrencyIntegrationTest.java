package com.ecommerce.monolith.payment;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.order.dto.OrderRequest;
import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.repository.OrderRepository;
import com.ecommerce.monolith.order.service.OrderService;
import com.ecommerce.monolith.payment.dto.PaymentRequest;
import com.ecommerce.monolith.payment.entity.Payment;
import com.ecommerce.monolith.payment.repository.PaymentRepository;
import com.ecommerce.monolith.payment.service.PaymentService;
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

class PaymentApprovalExpirationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void paymentApprovalAndPendingExpirationDoNotLeavePaymentOrderMismatch() throws InterruptedException {
        int initialStock = 4;
        int orderQuantity = 2;
        User user = userRepository.save(User.builder()
                .email("approve-expire-buyer@example.com")
                .password("encoded-password")
                .name("Approve Expire Buyer")
                .phoneNumber("010-7777-8888")
                .build());
        Product product = productRepository.save(Product.builder()
                .name("결제 만료 경쟁 상품")
                .price(BigDecimal.valueOf(15000))
                .stockQuantity(initialStock)
                .status(Product.ProductStatus.ACTIVE)
                .build());

        OrderResponse.OrderInfo createdOrder = orderService.createOrder(
                user.getUserId(),
                createOrderRequest(product.getProductId(), orderQuantity)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger paymentAttempts = new AtomicInteger();
        AtomicInteger expirationAttempts = new AtomicInteger();
        AtomicInteger expectedConflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        CompletableFuture<Void> paymentApproval = CompletableFuture.runAsync(() -> {
            readyLatch.countDown();
            await(startLatch);
            try {
                paymentService.requestPayment(paymentRequest(createdOrder.getOrderId()));
                paymentAttempts.incrementAndGet();
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.INVALID_ORDER_STATUS
                        || e.getErrorCode() == ErrorCode.ORDER_STATE_CONFLICT) {
                    expectedConflicts.incrementAndGet();
                    return;
                }
                unexpectedFailure.compareAndSet(null, e);
            } catch (ObjectOptimisticLockingFailureException e) {
                expectedConflicts.incrementAndGet();
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
                    expectedConflicts.incrementAndGet();
                    return;
                }
                unexpectedFailure.compareAndSet(null, e);
            } catch (ObjectOptimisticLockingFailureException e) {
                expectedConflicts.incrementAndGet();
            } catch (Exception e) {
                unexpectedFailure.compareAndSet(null, e);
            }
        }, executor);

        readyLatch.await();
        startLatch.countDown();
        CompletableFuture.allOf(paymentApproval, pendingExpiration).join();
        executor.shutdownNow();

        Order finalOrder = orderRepository.findById(createdOrder.getOrderId()).orElseThrow();
        Product finalProduct = productRepository.findById(product.getProductId()).orElseThrow();
        List<Payment> payments = paymentRepository.findAllByOrderId(createdOrder.getOrderId());

        assertThat(unexpectedFailure.get()).isNull();
        assertThat(paymentAttempts.get() + expirationAttempts.get() + expectedConflicts.get()).isGreaterThanOrEqualTo(1);
        assertThat(finalOrder.getStatus()).isIn(Order.OrderStatus.CONFIRMED, Order.OrderStatus.CANCELLED);

        if (finalOrder.getStatus() == Order.OrderStatus.CONFIRMED) {
            assertThat(payments).hasSize(1);
            assertThat(payments.get(0).getStatus()).isEqualTo(Payment.PaymentStatus.COMPLETED);
            assertThat(finalProduct.getStockQuantity()).isEqualTo(initialStock - orderQuantity);
            return;
        }

        assertThat(payments).isEmpty();
        assertThat(finalProduct.getStockQuantity()).isEqualTo(initialStock);
        assertThat(finalProduct.getStatus()).isEqualTo(Product.ProductStatus.ACTIVE);
    }

    private static OrderRequest.Create createOrderRequest(Long productId, int quantity) {
        OrderRequest.OrderItemRequest item = new OrderRequest.OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);

        OrderRequest.Create request = new OrderRequest.Create();
        request.setOrderItems(List.of(item));
        request.setShippingAddress(shippingAddressRequest());
        return request;
    }

    private static OrderRequest.ShippingAddressRequest shippingAddressRequest() {
        OrderRequest.ShippingAddressRequest request = new OrderRequest.ShippingAddressRequest();
        request.setZipCode("12345");
        request.setAddress("Seoul");
        request.setRecipientName("Approve Expire Buyer");
        request.setRecipientPhone("010-7777-8888");
        return request;
    }

    private static PaymentRequest.Create paymentRequest(Long orderId) {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(orderId);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setIdempotencyKey("approve-expire-key");
        request.setCardNumber("4111111111111112");
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
