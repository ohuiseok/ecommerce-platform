package com.ecommerce.monolith.order.repository;

import com.ecommerce.monolith.order.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    
    Page<Order> findByUserId(Long userId, Pageable pageable);
    
    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);
    
    List<Order> findByUserIdAndStatus(Long userId, Order.OrderStatus status);
    
    @Query("SELECT o FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
    List<Order> findByCreatedAtBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.userId = :userId AND o.status = :status")
    Long countByUserIdAndStatus(@Param("userId") Long userId, @Param("status") Order.OrderStatus status);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM payments p
                WHERE p.order_id = :orderId
                  AND p.status IN (:paymentStatuses)
            )
            """, nativeQuery = true)
    boolean existsPaymentByOrderIdAndStatusIn(
            @Param("orderId") Long orderId,
            @Param("paymentStatuses") Collection<String> paymentStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Order findByIdForUpdate(@Param("orderId") Long orderId);

    @Query(value = """
            SELECT o.*
            FROM orders o
            WHERE o.status = :status
              AND o.created_at <= :cutoff
              AND NOT EXISTS (
                  SELECT 1
                  FROM payments p
                  WHERE p.order_id = o.order_id
                    AND p.status IN (:excludedPaymentStatuses)
              )
            FOR UPDATE
            """, nativeQuery = true)
    List<Order> findExpirablePendingOrders(
            @Param("status") String status,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("excludedPaymentStatuses") Collection<String> excludedPaymentStatuses,
            Pageable pageable
    );
}
