package com.ecommerce.monolith.order;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.order.dto.OrderRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBurstIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void burstOrdersDoNotOversellSingleProduct() throws InterruptedException {
        int initialStock = 8;
        int concurrentOrders = 25;

        User user = userRepository.save(User.builder()
                .email("burst-buyer@example.com")
                .password("encoded-password")
                .name("Burst Buyer")
                .phoneNumber("010-1111-2222")
                .build());
        Product product = productRepository.save(Product.builder()
                .name("버스트 한정 상품")
                .price(BigDecimal.valueOf(30000))
                .stockQuantity(initialStock)
                .status(Product.ProductStatus.ACTIVE)
                .build());

        ExecutorService executor = Executors.newFixedThreadPool(concurrentOrders);
        CountDownLatch readyLatch = new CountDownLatch(concurrentOrders);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientStockCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        CompletableFuture<?>[] tasks = new CompletableFuture[concurrentOrders];
        for (int i = 0; i < concurrentOrders; i++) {
            tasks[i] = CompletableFuture.runAsync(() -> {
                readyLatch.countDown();
                await(startLatch);

                try {
                    orderService.createOrder(user.getUserId(), createOrderRequest(product.getProductId()));
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                        insufficientStockCount.incrementAndGet();
                        return;
                    }
                    unexpectedFailure.compareAndSet(null, e);
                } catch (Exception e) {
                    unexpectedFailure.compareAndSet(null, e);
                }
            }, executor);
        }

        readyLatch.await();
        startLatch.countDown();
        CompletableFuture.allOf(tasks).join();
        executor.shutdownNow();

        Product finalProduct = productRepository.findById(product.getProductId()).orElseThrow();

        assertThat(unexpectedFailure.get()).isNull();
        assertThat(successCount.get()).isEqualTo(initialStock);
        assertThat(insufficientStockCount.get()).isEqualTo(concurrentOrders - initialStock);
        assertThat(orderRepository.countByUserIdAndStatus(user.getUserId(), Order.OrderStatus.PENDING))
                .isEqualTo(initialStock);
        assertThat(finalProduct.getStockQuantity()).isZero();
        assertThat(finalProduct.getStatus()).isEqualTo(Product.ProductStatus.OUT_OF_STOCK);
    }

    private static OrderRequest.Create createOrderRequest(Long productId) {
        OrderRequest.OrderItemRequest item = new OrderRequest.OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);

        OrderRequest.Create request = new OrderRequest.Create();
        request.setOrderItems(List.of(item));
        request.setShippingAddress(shippingAddressRequest());
        return request;
    }

    private static OrderRequest.ShippingAddressRequest shippingAddressRequest() {
        OrderRequest.ShippingAddressRequest request = new OrderRequest.ShippingAddressRequest();
        request.setZipCode("12345");
        request.setAddress("Seoul");
        request.setRecipientName("Burst Buyer");
        request.setRecipientPhone("010-1111-2222");
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
