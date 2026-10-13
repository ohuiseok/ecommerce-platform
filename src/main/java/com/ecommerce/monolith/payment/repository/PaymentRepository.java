package com.ecommerce.monolith.payment.repository;

import com.ecommerce.monolith.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    List<Payment> findAllByOrderId(Long orderId);

    Optional<Payment> findByOrderIdAndIdempotencyKey(Long orderId, String idempotencyKey);

    boolean existsByOrderIdAndStatus(Long orderId, Payment.PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    Integer lockIdempotencyKey(@Param("lockKey") String lockKey);

    @Query(value = """
            SELECT
                o.order_id AS "orderId",
                o.user_id AS "orderUserId",
                o.status AS "orderStatus",
                p.payment_id AS "paymentId",
                p.status AS "paymentStatus",
                p.amount AS "paymentAmount",
                CASE
                    WHEN p.payment_id IS NULL THEN 'CONFIRMED_ORDER_WITHOUT_PAYMENT'
                    ELSE 'COMPLETED_PAYMENT_ORDER_NOT_CONFIRMED'
                END AS "mismatchType",
                o.updated_at AS "orderUpdatedAt",
                p.updated_at AS "paymentUpdatedAt"
            FROM orders o
            LEFT JOIN payments p ON p.order_id = o.order_id
            WHERE (p.status = 'COMPLETED' AND o.status <> 'CONFIRMED')
               OR (o.status = 'CONFIRMED' AND p.payment_id IS NULL)
            ORDER BY COALESCE(p.updated_at, o.updated_at) DESC, o.order_id DESC
            """, nativeQuery = true)
    List<PaymentOrderMismatchProjection> findPaymentOrderMismatches();
}
