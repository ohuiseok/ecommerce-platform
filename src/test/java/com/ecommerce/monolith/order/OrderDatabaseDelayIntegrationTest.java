package com.ecommerce.monolith.order;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDatabaseDelayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void orderCreationWaitsForDelayedStockUpdateAndKeepsConsistentState() throws Exception {
        User user = userRepository.save(User.builder()
                .email("delayed-db-buyer@example.com")
                .password("encoded-password")
                .name("Delayed Buyer")
                .phoneNumber("010-3333-4444")
                .build());
        Product product = productRepository.save(Product.builder()
                .name("DB 지연 검증 상품")
                .price(BigDecimal.valueOf(42000))
                .stockQuantity(1)
                .status(Product.ProductStatus.ACTIVE)
                .build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        CompletableFuture<Void> rowLock = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject(
                            "SELECT product_id FROM products WHERE product_id = ? FOR UPDATE",
                            Long.class,
                            product.getProductId()
                    );
                    lockAcquired.countDown();
                    await(releaseLock);
                }), executor);

        assertThat(lockAcquired.await(3, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<OrderResponse.OrderInfo> orderAttempt = CompletableFuture.supplyAsync(() ->
                orderService.createOrder(user.getUserId(), createOrderRequest(product.getProductId())), executor);

        Thread.sleep(300);
        assertThat(orderAttempt).isNotDone();

        releaseLock.countDown();

        OrderResponse.OrderInfo createdOrder = orderAttempt.get(3, TimeUnit.SECONDS);
        rowLock.get(3, TimeUnit.SECONDS);
        executor.shutdownNow();

        Product finalProduct = productRepository.findById(product.getProductId()).orElseThrow();

        assertThat(createdOrder.getStatus()).isEqualTo(Order.OrderStatus.PENDING);
        assertThat(orderRepository.countByUserIdAndStatus(user.getUserId(), Order.OrderStatus.PENDING))
                .isEqualTo(1);
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
        request.setRecipientName("Delayed Buyer");
        request.setRecipientPhone("010-3333-4444");
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
