package com.ecommerce.monolith.order;

import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.repository.OrderRepository;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderOptimisticLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void concurrentOrderStatusChangesConflictOnVersion() {
        Order savedOrder = orderRepository.saveAndFlush(Order.builder()
                .userId(1001L)
                .totalAmount(BigDecimal.valueOf(1000))
                .originalAmount(BigDecimal.valueOf(1000))
                .discountAmount(BigDecimal.ZERO)
                .status(Order.OrderStatus.PENDING)
                .build());

        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();
        EntityTransaction firstTransaction = firstEntityManager.getTransaction();
        EntityTransaction secondTransaction = secondEntityManager.getTransaction();

        try {
            firstTransaction.begin();
            secondTransaction.begin();

            Order firstOrder = firstEntityManager.find(Order.class, savedOrder.getOrderId());
            Order secondOrder = secondEntityManager.find(Order.class, savedOrder.getOrderId());

            firstOrder.updateStatus(Order.OrderStatus.CONFIRMED);
            firstTransaction.commit();

            secondOrder.updateStatus(Order.OrderStatus.CANCELLED);
            assertThatThrownBy(secondTransaction::commit)
                    .isInstanceOf(RollbackException.class);

            Order freshOrder = orderRepository.findById(savedOrder.getOrderId()).orElseThrow();
            assertThat(freshOrder.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(freshOrder.getVersion()).isEqualTo(1L);
        } finally {
            rollbackIfActive(firstTransaction);
            rollbackIfActive(secondTransaction);
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    private void rollbackIfActive(EntityTransaction transaction) {
        if (transaction.isActive()) {
            transaction.rollback();
        }
    }
}
