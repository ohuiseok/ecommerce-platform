package com.ecommerce.monolith.outbox.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "outbox_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_outbox_events_event_id",
                columnNames = "event_id"
        ),
        indexes = @Index(
                name = "idx_outbox_events_status_available",
                columnList = "status, available_at, outbox_event_id"
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long outboxEventId;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false)
    private AggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum EventType {
        ORDER_CREATED,
        ORDER_CANCELLED,
        PAYMENT_COMPLETED,
        PAYMENT_FAILED,
        PAYMENT_RECONCILIATION_REQUIRED
    }

    public enum AggregateType {
        ORDER,
        PAYMENT,
        RECONCILIATION
    }

    public enum OutboxStatus {
        PENDING,
        PUBLISHED,
        FAILED,
        DEAD_LETTER
    }

    public void markPublished(LocalDateTime publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void markFailed(String reason, LocalDateTime failedAt, Duration retryDelay, int maxRetryCount) {
        if (maxRetryCount < 1) {
            throw new IllegalArgumentException("Outbox 최대 재시도 횟수는 1 이상이어야 합니다.");
        }

        this.retryCount += 1;
        this.lastError = reason;
        this.publishedAt = null;

        if (this.retryCount >= maxRetryCount) {
            this.status = OutboxStatus.DEAD_LETTER;
            this.availableAt = failedAt;
            return;
        }

        this.status = OutboxStatus.FAILED;
        this.availableAt = failedAt.plus(retryDelay);
    }
}
