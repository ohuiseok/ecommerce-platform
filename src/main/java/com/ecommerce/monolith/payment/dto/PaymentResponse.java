package com.ecommerce.monolith.payment.dto;

import com.ecommerce.monolith.payment.entity.Payment;
import com.ecommerce.monolith.payment.entity.PaymentReconciliationTask;
import com.ecommerce.monolith.payment.entity.PaymentWebhookEvent;
import com.ecommerce.monolith.payment.repository.PaymentOrderMismatchProjection;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentResponse {

    @Data
    @Builder
    public static class PaymentInfo {
        private Long paymentId;
        private Long orderId;
        private Long userId;
        private BigDecimal amount;
        private Payment.PaymentMethod method;
        private Payment.PaymentStatus status;
        private String idempotencyKey;
        private String pgTransactionId;
        private String failureReason;
        private LocalDateTime approvedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static PaymentInfo from(Payment payment) {
            return PaymentInfo.builder()
                    .paymentId(payment.getPaymentId())
                    .orderId(payment.getOrderId())
                    .userId(payment.getUserId())
                    .amount(payment.getAmount())
                    .method(payment.getMethod())
                    .status(payment.getStatus())
                    .idempotencyKey(payment.getIdempotencyKey())
                    .pgTransactionId(payment.getPgTransactionId())
                    .failureReason(payment.getFailureReason())
                    .approvedAt(payment.getApprovedAt())
                    .createdAt(payment.getCreatedAt())
                    .updatedAt(payment.getUpdatedAt())
                    .build();
        }
    }

    @Data
    @Builder
    public static class PaymentOrderMismatchInfo {
        private Long orderId;
        private Long orderUserId;
        private String orderStatus;
        private Long paymentId;
        private String paymentStatus;
        private BigDecimal paymentAmount;
        private String mismatchType;
        private LocalDateTime orderUpdatedAt;
        private LocalDateTime paymentUpdatedAt;

        public static PaymentOrderMismatchInfo from(PaymentOrderMismatchProjection projection) {
            return PaymentOrderMismatchInfo.builder()
                    .orderId(projection.getOrderId())
                    .orderUserId(projection.getOrderUserId())
                    .orderStatus(projection.getOrderStatus())
                    .paymentId(projection.getPaymentId())
                    .paymentStatus(projection.getPaymentStatus())
                    .paymentAmount(projection.getPaymentAmount())
                    .mismatchType(projection.getMismatchType())
                    .orderUpdatedAt(projection.getOrderUpdatedAt())
                    .paymentUpdatedAt(projection.getPaymentUpdatedAt())
                    .build();
        }
    }

    @Data
    @Builder
    public static class PaymentReconciliationTaskInfo {
        private Long taskId;
        private PaymentReconciliationTask.ReconciliationType type;
        private PaymentReconciliationTask.ReconciliationStatus status;
        private Long orderId;
        private Long userId;
        private Long paymentId;
        private String idempotencyKey;
        private Payment.PaymentMethod paymentMethod;
        private String pgEventId;
        private String pgDeliveryId;
        private String pgTransactionId;
        private BigDecimal amount;
        private String reason;
        private LocalDateTime pgOccurredAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static PaymentReconciliationTaskInfo from(PaymentReconciliationTask task) {
            return PaymentReconciliationTaskInfo.builder()
                    .taskId(task.getTaskId())
                    .type(task.getType())
                    .status(task.getStatus())
                    .orderId(task.getOrderId())
                    .userId(task.getUserId())
                    .paymentId(task.getPaymentId())
                    .idempotencyKey(task.getIdempotencyKey())
                    .paymentMethod(task.getPaymentMethod())
                    .pgEventId(task.getPgEventId())
                    .pgDeliveryId(task.getPgDeliveryId())
                    .pgTransactionId(task.getPgTransactionId())
                    .amount(task.getAmount())
                    .reason(task.getReason())
                    .pgOccurredAt(task.getPgOccurredAt())
                    .createdAt(task.getCreatedAt())
                    .updatedAt(task.getUpdatedAt())
                    .build();
        }
    }

    @Data
    @Builder
    public static class PaymentWebhookEventInfo {
        private Long webhookEventId;
        private String pgEventId;
        private String pgDeliveryId;
        private String eventType;
        private Long orderId;
        private String pgTransactionId;
        private BigDecimal amount;
        private PaymentWebhookEvent.WebhookStatus status;
        private String failureReason;
        private LocalDateTime pgOccurredAt;
        private LocalDateTime receivedAt;
        private LocalDateTime processedAt;

        public static PaymentWebhookEventInfo from(PaymentWebhookEvent event) {
            return PaymentWebhookEventInfo.builder()
                    .webhookEventId(event.getWebhookEventId())
                    .pgEventId(event.getPgEventId())
                    .pgDeliveryId(event.getPgDeliveryId())
                    .eventType(event.getEventType().name())
                    .orderId(event.getOrderId())
                    .pgTransactionId(event.getPgTransactionId())
                    .amount(event.getAmount())
                    .status(event.getStatus())
                    .failureReason(event.getFailureReason())
                    .pgOccurredAt(event.getPgOccurredAt())
                    .receivedAt(event.getReceivedAt())
                    .processedAt(event.getProcessedAt())
                    .build();
        }
    }
}
