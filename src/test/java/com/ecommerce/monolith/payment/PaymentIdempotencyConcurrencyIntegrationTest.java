package com.ecommerce.monolith.payment;

import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.entity.ShippingAddress;
import com.ecommerce.monolith.order.repository.OrderRepository;
import com.ecommerce.monolith.payment.client.PgClient;
import com.ecommerce.monolith.payment.dto.PaymentRequest;
import com.ecommerce.monolith.payment.dto.PaymentResponse;
import com.ecommerce.monolith.payment.entity.Payment;
import com.ecommerce.monolith.payment.repository.PaymentRepository;
import com.ecommerce.monolith.payment.service.PaymentService;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIdempotencyConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ControllablePgClient pgClient;

    @BeforeEach
    void setUp() {
        pgClient.reset();
    }

    @Test
    void concurrentSameIdempotencyKeyReturnsSinglePaymentResult() throws InterruptedException {
        int concurrentRequests = 8;
        Order order = savePendingOrder();

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch readyLatch = new CountDownLatch(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        Set<Long> paymentIds = ConcurrentHashMap.newKeySet();

        CompletableFuture<?>[] tasks = new CompletableFuture[concurrentRequests];
        for (int i = 0; i < concurrentRequests; i++) {
            tasks[i] = CompletableFuture.runAsync(() -> {
                readyLatch.countDown();
                await(startLatch);

                PaymentResponse.PaymentInfo response = paymentService.requestPayment(paymentRequest(order.getOrderId()));
                paymentIds.add(response.getPaymentId());
            }, executor);
        }

        readyLatch.await();
        startLatch.countDown();
        CompletableFuture.allOf(tasks).join();
        executor.shutdown();

        List<Payment> payments = paymentRepository.findAllByOrderId(order.getOrderId());
        Order reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();

        assertThat(paymentIds).hasSize(1);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.PaymentStatus.COMPLETED);
        assertThat(payments.get(0).getIdempotencyKey()).isEqualTo("same-payment-key");
        assertThat(reloadedOrder.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(pgClient.chargeCount()).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyRetryAfterPgTimeoutReturnsExistingProcessingPaymentWithoutSecondPgCall() {
        pgClient.throwOnCharge(new RuntimeException("PG 응답 타임아웃"));
        Order order = savePendingOrder();
        PaymentRequest.Create request = paymentRequest(order.getOrderId());

        assertThatThrownBy(() -> paymentService.requestPayment(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("PG 응답 타임아웃");

        List<Payment> paymentsAfterTimeout = paymentRepository.findAllByOrderId(order.getOrderId());
        assertThat(paymentsAfterTimeout).hasSize(1);
        assertThat(paymentsAfterTimeout.get(0).getStatus()).isEqualTo(Payment.PaymentStatus.PROCESSING);
        assertThat(pgClient.chargeCount()).isEqualTo(1);

        pgClient.succeedWith(PgClient.PgResult.success("MOCK-TX-RETRY-SHOULD-NOT-BE-CALLED"));

        PaymentResponse.PaymentInfo retryResult = paymentService.requestPayment(request);

        List<Payment> paymentsAfterRetry = paymentRepository.findAllByOrderId(order.getOrderId());
        Order reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();

        assertThat(retryResult.getPaymentId()).isEqualTo(paymentsAfterTimeout.get(0).getPaymentId());
        assertThat(retryResult.getStatus()).isEqualTo(Payment.PaymentStatus.PROCESSING);
        assertThat(paymentsAfterRetry).hasSize(1);
        assertThat(paymentsAfterRetry.get(0).getStatus()).isEqualTo(Payment.PaymentStatus.PROCESSING);
        assertThat(paymentsAfterRetry.get(0).getPgTransactionId()).isNull();
        assertThat(reloadedOrder.getStatus()).isEqualTo(Order.OrderStatus.PENDING);
        assertThat(pgClient.chargeCount()).isEqualTo(1);
    }

    private PaymentRequest.Create paymentRequest(Long orderId) {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(orderId);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setIdempotencyKey("same-payment-key");
        request.setCardNumber("4111111111111112");
        return request;
    }

    private Order savePendingOrder() {
        return orderRepository.save(Order.builder()
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(12000))
                .shippingAddress(ShippingAddress.builder()
                        .zipCode("12345")
                        .address("Seoul")
                        .recipientName("Buyer")
                        .recipientPhone("010-0000-0000")
                        .build())
                .build());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class PgClientTestConfig {

        @Bean
        @Primary
        ControllablePgClient controllablePgClient() {
            return new ControllablePgClient();
        }
    }

    static class ControllablePgClient implements PgClient {

        private final AtomicInteger chargeCount = new AtomicInteger();
        private final AtomicReference<PgResult> result =
                new AtomicReference<>(PgResult.success("MOCK-TX-SUCCESS"));
        private final AtomicReference<RuntimeException> exception = new AtomicReference<>();

        @Override
        public PgResult charge(BigDecimal amount, Payment.PaymentMethod method, String cardNumber) {
            chargeCount.incrementAndGet();
            RuntimeException chargeException = exception.get();
            if (chargeException != null) {
                throw chargeException;
            }

            return result.get();
        }

        void reset() {
            chargeCount.set(0);
            result.set(PgResult.success("MOCK-TX-SUCCESS"));
            exception.set(null);
        }

        void succeedWith(PgResult pgResult) {
            result.set(pgResult);
            exception.set(null);
        }

        void throwOnCharge(RuntimeException chargeException) {
            exception.set(chargeException);
        }

        int chargeCount() {
            return chargeCount.get();
        }
    }
}
