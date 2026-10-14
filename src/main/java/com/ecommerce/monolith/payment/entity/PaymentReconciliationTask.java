package com.ecommerce.monolith.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment_reconciliation_tasks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_reconciliation_tasks_pg_event_id",
                        columnNames = "pg_event_id"
                ),
                @UniqueConstraint(
                        name = "uk_payment_reconciliation_tasks_payment_id",
                        columnNames = "payment_id"
                )
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PaymentReconciliationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReconciliationStatus status = ReconciliationStatus.OPEN;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private Payment.PaymentMethod paymentMethod;

    @Column(name = "pg_event_id")
    private String pgEventId;

    @Column(name = "pg_delivery_id")
    private String pgDeliveryId;

    @Column(name = "pg_transaction_id")
    private String pgTransactionId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Column(name = "pg_occurred_at")
    private LocalDateTime pgOccurredAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ReconciliationType {
        LATE_PAYMENT_APPROVED_AFTER_ORDER_CANCELLED,
        PG_TIMEOUT_PROCESSING_PAYMENT
    }

    public enum ReconciliationStatus {
        OPEN, RESOLVED
    }
}
