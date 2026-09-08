package com.ecommerce.monolith.payment.service;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.service.OrderService;
import com.ecommerce.monolith.outbox.service.OutboxEventService;
import com.ecommerce.monolith.payment.client.MockPgClient;
import com.ecommerce.monolith.payment.dto.PaymentRequest;
import com.ecommerce.monolith.payment.dto.PaymentResponse;
import com.ecommerce.monolith.payment.entity.Payment;
import com.ecommerce.monolith.payment.entity.PaymentReconciliationTask;
import com.ecommerce.monolith.payment.entity.PaymentWebhookEvent;
import com.ecommerce.monolith.payment.repository.PaymentRepository;
import com.ecommerce.monolith.payment.repository.PaymentReconciliationTaskRepository;
import com.ecommerce.monolith.payment.repository.PaymentWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentReconciliationTaskRepository reconciliationTaskRepository;

    @Mock
    private PaymentWebhookEventRepository webhookEventRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private MockPgClient mockPgClient;

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void requestPaymentConfirmsOrderWhenChargeSucceeds() {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(1L);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setCardNumber("1234567890123452");
        request.setIdempotencyKey(" pay-key-1 ");

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(2000))
                .status(Order.OrderStatus.PENDING)
                .build());
        when(paymentRepository.findByOrderIdAndIdempotencyKey(1L, "pay-key-1")).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderIdAndStatus(1L, Payment.PaymentStatus.COMPLETED)).thenReturn(false);
        when(mockPgClient.charge(BigDecimal.valueOf(2000), Payment.PaymentMethod.CARD, "1234567890123452"))
                .thenReturn(MockPgClient.PgResult.success("MOCK-TX-1"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setPaymentId(100L);
            return payment;
        });

        PaymentResponse.PaymentInfo result = paymentService.requestPayment(request);

        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.COMPLETED);
        assertThat(result.getPgTransactionId()).isEqualTo("MOCK-TX-1");
        assertThat(result.getIdempotencyKey()).isEqualTo("pay-key-1");
        verify(orderService).markOrderConfirmed(1L);
        verify(outboxEventService).recordPaymentCompleted(any(Payment.class));
    }

    @Test
    void requestPaymentCancelsPendingOrderWhenChargeFails() {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(1L);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setCardNumber("1234567890123451");
        request.setIdempotencyKey("pay-key-2");

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(2000))
                .status(Order.OrderStatus.PENDING)
                .build());
        when(paymentRepository.findByOrderIdAndIdempotencyKey(1L, "pay-key-2")).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderIdAndStatus(1L, Payment.PaymentStatus.COMPLETED)).thenReturn(false);
        when(mockPgClient.charge(BigDecimal.valueOf(2000), Payment.PaymentMethod.CARD, "1234567890123451"))
                .thenReturn(MockPgClient.PgResult.failure("카드 승인이 거절되었습니다"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse.PaymentInfo result = paymentService.requestPayment(request);

        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.FAILED);
        assertThat(result.getFailureReason()).isEqualTo("카드 승인이 거절되었습니다");
        verify(orderService, never()).markOrderConfirmed(any());
        verify(orderService).cancelPendingOrderAfterPaymentFailure(1L);
        verify(outboxEventService).recordPaymentFailed(any(Payment.class));
    }

    @Test
    void requestPaymentRejectsCancelledOrderBeforePgCall() {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(1L);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setCardNumber("1234567890123452");
        request.setIdempotencyKey("pay-key-cancelled");

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(2000))
                .status(Order.OrderStatus.CANCELLED)
                .build());
        when(paymentRepository.findByOrderIdAndIdempotencyKey(1L, "pay-key-cancelled")).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderIdAndStatus(1L, Payment.PaymentStatus.COMPLETED)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.requestPayment(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS);

        verify(mockPgClient, never()).charge(any(), any(), any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void requestPaymentThrowsWhenAlreadyCompleted() {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(1L);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setIdempotencyKey("pay-key-3");

        Payment existing = Payment.builder()
                .paymentId(1L)
                .orderId(1L)
                .userId(1L)
                .amount(BigDecimal.valueOf(2000))
                .method(Payment.PaymentMethod.CARD)
                .status(Payment.PaymentStatus.COMPLETED)
                .build();

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(2000))
                .status(Order.OrderStatus.CONFIRMED)
                .build());
        when(paymentRepository.findByOrderIdAndIdempotencyKey(1L, "pay-key-3")).thenReturn(Optional.empty());
        when(paymentRepository.existsByOrderIdAndStatus(1L, Payment.PaymentStatus.COMPLETED)).thenReturn(true);

        assertThatThrownBy(() -> paymentService.requestPayment(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    @Test
    void requestPaymentReturnsExistingPaymentForSameIdempotencyKeyWithoutPgCall() {
        PaymentRequest.Create request = new PaymentRequest.Create();
        request.setOrderId(1L);
        request.setMethod(Payment.PaymentMethod.CARD);
        request.setCardNumber("1234567890123452");
        request.setIdempotencyKey("pay-key-4");

        Payment existing = Payment.builder()
                .paymentId(100L)
                .orderId(1L)
                .userId(1L)
                .amount(BigDecimal.valueOf(2000))
                .method(Payment.PaymentMethod.CARD)
                .status(Payment.PaymentStatus.FAILED)
                .idempotencyKey("pay-key-4")
                .failureReason("카드 승인이 거절되었습니다")
                .build();

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(1L)
                .totalAmount(BigDecimal.valueOf(2000))
                .status(Order.OrderStatus.PENDING)
                .build());
        when(paymentRepository.findByOrderIdAndIdempotencyKey(1L, "pay-key-4")).thenReturn(Optional.of(existing));

        PaymentResponse.PaymentInfo result = paymentService.requestPayment(request);

        assertThat(result.getPaymentId()).isEqualTo(100L);
        assertThat(result.getStatus()).isEqualTo(Payment.PaymentStatus.FAILED);
        assertThat(result.getIdempotencyKey()).isEqualTo("pay-key-4");
        verify(mockPgClient, never()).charge(any(), any(), any());
        verify(paymentRepository, never()).save(any());
        verify(orderService, never()).markOrderConfirmed(any());
        verify(outboxEventService, never()).recordPaymentCompleted(any());
        verify(outboxEventService, never()).recordPaymentFailed(any());
    }

    @Test
    void paymentResponseIncludesNullableIdempotencyKey() {
        Payment payment = Payment.builder()
                .paymentId(1L)
                .orderId(1L)
                .userId(1L)
                .amount(BigDecimal.valueOf(2000))
                .method(Payment.PaymentMethod.CARD)
                .status(Payment.PaymentStatus.PENDING)
                .idempotencyKey("pay-key-1")
                .build();

        PaymentResponse.PaymentInfo result = PaymentResponse.PaymentInfo.from(payment);

        assertThat(result.getIdempotencyKey()).isEqualTo("pay-key-1");
    }

    @Test
    void getPaymentOrderMismatchesReturnsRepositoryProjection() {
        LocalDateTime orderUpdatedAt = LocalDateTime.of(2026, 8, 26, 10, 0);
        LocalDateTime paymentUpdatedAt = LocalDateTime.of(2026, 8, 26, 10, 5);

        when(paymentRepository.findPaymentOrderMismatches()).thenReturn(List.of(new TestMismatchProjection(
                10L,
                20L,
                "PENDING",
                30L,
                "COMPLETED",
                BigDecimal.valueOf(12000),
                "COMPLETED_PAYMENT_ORDER_NOT_CONFIRMED",
                orderUpdatedAt,
                paymentUpdatedAt
        )));

        List<PaymentResponse.PaymentOrderMismatchInfo> result = paymentService.getPaymentOrderMismatches();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo(10L);
        assertThat(result.get(0).getOrderUserId()).isEqualTo(20L);
        assertThat(result.get(0).getOrderStatus()).isEqualTo("PENDING");
        assertThat(result.get(0).getPaymentId()).isEqualTo(30L);
        assertThat(result.get(0).getPaymentStatus()).isEqualTo("COMPLETED");
        assertThat(result.get(0).getPaymentAmount()).isEqualByComparingTo("12000");
        assertThat(result.get(0).getMismatchType()).isEqualTo("COMPLETED_PAYMENT_ORDER_NOT_CONFIRMED");
        assertThat(result.get(0).getOrderUpdatedAt()).isEqualTo(orderUpdatedAt);
        assertThat(result.get(0).getPaymentUpdatedAt()).isEqualTo(paymentUpdatedAt);
    }

    @Test
    void registerLateApprovedPaymentForReconciliationCreatesOpenTaskForCancelledOrder() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 3, 12, 30);
        MockPgClient.PgEvent event = new MockPgClient.PgEvent(
                "MOCK-EVENT-LATE-1",
                "MOCK-DELIVERY-1",
                MockPgClient.PgEventType.PAYMENT_APPROVED,
                1L,
                "MOCK-TX-LATE-1",
                BigDecimal.valueOf(12000),
                null,
                occurredAt
        );

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(10L)
                .totalAmount(BigDecimal.valueOf(12000))
                .status(Order.OrderStatus.CANCELLED)
                .build());
        when(reconciliationTaskRepository.findByPgEventId("MOCK-EVENT-LATE-1")).thenReturn(Optional.empty());
        when(reconciliationTaskRepository.save(any(PaymentReconciliationTask.class))).thenAnswer(invocation -> {
            PaymentReconciliationTask task = invocation.getArgument(0);
            task.setTaskId(100L);
            return task;
        });

        Optional<PaymentResponse.PaymentReconciliationTaskInfo> result =
                paymentService.registerLateApprovedPaymentForReconciliation(event);

        assertThat(result).isPresent();
        PaymentResponse.PaymentReconciliationTaskInfo task = result.get();
        assertThat(task.getTaskId()).isEqualTo(100L);
        assertThat(task.getType()).isEqualTo(
                PaymentReconciliationTask.ReconciliationType.LATE_PAYMENT_APPROVED_AFTER_ORDER_CANCELLED
        );
        assertThat(task.getStatus()).isEqualTo(PaymentReconciliationTask.ReconciliationStatus.OPEN);
        assertThat(task.getOrderId()).isEqualTo(1L);
        assertThat(task.getUserId()).isEqualTo(10L);
        assertThat(task.getPgEventId()).isEqualTo("MOCK-EVENT-LATE-1");
        assertThat(task.getPgDeliveryId()).isEqualTo("MOCK-DELIVERY-1");
        assertThat(task.getPgTransactionId()).isEqualTo("MOCK-TX-LATE-1");
        assertThat(task.getAmount()).isEqualByComparingTo("12000");
        assertThat(task.getReason()).contains("늦은 결제 승인");
        assertThat(task.getPgOccurredAt()).isEqualTo(occurredAt);
        verify(outboxEventService).recordPaymentReconciliationRequired(any(PaymentReconciliationTask.class));
    }

    @Test
    void registerLateApprovedPaymentForReconciliationReturnsExistingTaskForSamePgEvent() {
        MockPgClient.PgEvent event = MockPgClient.PgEvent.approved(1L, "MOCK-TX-LATE-1", BigDecimal.valueOf(12000));
        PaymentReconciliationTask existing = PaymentReconciliationTask.builder()
                .taskId(100L)
                .type(PaymentReconciliationTask.ReconciliationType.LATE_PAYMENT_APPROVED_AFTER_ORDER_CANCELLED)
                .status(PaymentReconciliationTask.ReconciliationStatus.OPEN)
                .orderId(1L)
                .userId(10L)
                .pgEventId(event.eventId())
                .pgDeliveryId("MOCK-DELIVERY-FIRST")
                .pgTransactionId(event.transactionId())
                .amount(event.amount())
                .reason("취소된 주문에 늦은 결제 승인 이벤트가 도착했습니다. PG 환불 또는 수동 보정이 필요합니다.")
                .pgOccurredAt(event.occurredAt())
                .build();

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(10L)
                .totalAmount(BigDecimal.valueOf(12000))
                .status(Order.OrderStatus.CANCELLED)
                .build());
        when(reconciliationTaskRepository.findByPgEventId(event.eventId())).thenReturn(Optional.of(existing));

        Optional<PaymentResponse.PaymentReconciliationTaskInfo> result =
                paymentService.registerLateApprovedPaymentForReconciliation(event.redelivered());

        assertThat(result).isPresent();
        assertThat(result.get().getTaskId()).isEqualTo(100L);
        assertThat(result.get().getPgDeliveryId()).isEqualTo("MOCK-DELIVERY-FIRST");
        verify(reconciliationTaskRepository, never()).save(any());
        verify(outboxEventService, never()).recordPaymentReconciliationRequired(any());
    }

    @Test
    void registerLateApprovedPaymentForReconciliationIgnoresNonCancelledOrder() {
        MockPgClient.PgEvent event = MockPgClient.PgEvent.approved(1L, "MOCK-TX-1", BigDecimal.valueOf(12000));

        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(10L)
                .totalAmount(BigDecimal.valueOf(12000))
                .status(Order.OrderStatus.CONFIRMED)
                .build());

        Optional<PaymentResponse.PaymentReconciliationTaskInfo> result =
                paymentService.registerLateApprovedPaymentForReconciliation(event);

        assertThat(result).isEmpty();
        verify(reconciliationTaskRepository, never()).findByPgEventId(any());
        verify(reconciliationTaskRepository, never()).save(any());
    }

    @Test
    void getOpenPaymentReconciliationTasksReturnsOpenTasks() {
        PaymentReconciliationTask existing = PaymentReconciliationTask.builder()
                .taskId(100L)
                .type(PaymentReconciliationTask.ReconciliationType.LATE_PAYMENT_APPROVED_AFTER_ORDER_CANCELLED)
                .status(PaymentReconciliationTask.ReconciliationStatus.OPEN)
                .orderId(1L)
                .userId(10L)
                .pgEventId("MOCK-EVENT-LATE-1")
                .pgDeliveryId("MOCK-DELIVERY-1")
                .pgTransactionId("MOCK-TX-LATE-1")
                .amount(BigDecimal.valueOf(12000))
                .reason("취소된 주문에 늦은 결제 승인 이벤트가 도착했습니다. PG 환불 또는 수동 보정이 필요합니다.")
                .pgOccurredAt(LocalDateTime.of(2026, 9, 3, 12, 30))
                .build();

        when(reconciliationTaskRepository.findByStatusOrderByCreatedAtDesc(
                PaymentReconciliationTask.ReconciliationStatus.OPEN
        )).thenReturn(List.of(existing));

        List<PaymentResponse.PaymentReconciliationTaskInfo> result =
                paymentService.getOpenPaymentReconciliationTasks();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTaskId()).isEqualTo(100L);
        assertThat(result.get(0).getStatus()).isEqualTo(PaymentReconciliationTask.ReconciliationStatus.OPEN);
    }

    @Test
    void processPaymentWebhookEventConfirmsPendingOrderForApprovedEvent() {
        MockPgClient.PgEvent event = MockPgClient.PgEvent.approved(1L, "MOCK-TX-1", BigDecimal.valueOf(12000));

        when(webhookEventRepository.findByPgEventId(event.eventId())).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(invocation -> {
            PaymentWebhookEvent webhookEvent = invocation.getArgument(0);
            webhookEvent.setWebhookEventId(200L);
            return webhookEvent;
        });
        when(orderService.getOrder(1L)).thenReturn(OrderResponse.OrderInfo.builder()
                .orderId(1L)
                .userId(10L)
                .totalAmount(BigDecimal.valueOf(12000))
                .status(Order.OrderStatus.PENDING)
                .build());

        PaymentResponse.PaymentWebhookEventInfo result = paymentService.processPaymentWebhookEvent(event);

        assertThat(result.getWebhookEventId()).isEqualTo(200L);
        assertThat(result.getPgEventId()).isEqualTo(event.eventId());
        assertThat(result.getPgDeliveryId()).isEqualTo(event.deliveryId());
        assertThat(result.getStatus()).isEqualTo(PaymentWebhookEvent.WebhookStatus.PROCESSED);
        assertThat(result.getProcessedAt()).isNotNull();
        verify(orderService).markOrderConfirmed(1L);
    }

    @Test
    void processPaymentWebhookEventReturnsExistingResultForDuplicateDeliveryWithoutStateChange() {
        MockPgClient.PgEvent original = MockPgClient.PgEvent.approved(1L, "MOCK-TX-1", BigDecimal.valueOf(12000));
        MockPgClient.PgEvent duplicate = original.redelivered();
        PaymentWebhookEvent existing = PaymentWebhookEvent.received(original);
        existing.setWebhookEventId(200L);
        existing.markProcessed();

        when(webhookEventRepository.findByPgEventId(original.eventId())).thenReturn(Optional.of(existing));

        PaymentResponse.PaymentWebhookEventInfo result = paymentService.processPaymentWebhookEvent(duplicate);

        assertThat(result.getWebhookEventId()).isEqualTo(200L);
        assertThat(result.getPgEventId()).isEqualTo(original.eventId());
        assertThat(result.getPgDeliveryId()).isEqualTo(original.deliveryId());
        assertThat(result.getStatus()).isEqualTo(PaymentWebhookEvent.WebhookStatus.PROCESSED);
        verify(orderService, never()).markOrderConfirmed(any());
        verify(webhookEventRepository, never()).save(any());
    }

    private record TestMismatchProjection(
            Long orderId,
            Long orderUserId,
            String orderStatus,
            Long paymentId,
            String paymentStatus,
            BigDecimal paymentAmount,
            String mismatchType,
            LocalDateTime orderUpdatedAt,
            LocalDateTime paymentUpdatedAt
    ) implements com.ecommerce.monolith.payment.repository.PaymentOrderMismatchProjection {

        @Override
        public Long getOrderId() {
            return orderId;
        }

        @Override
        public Long getOrderUserId() {
            return orderUserId;
        }

        @Override
        public String getOrderStatus() {
            return orderStatus;
        }

        @Override
        public Long getPaymentId() {
            return paymentId;
        }

        @Override
        public String getPaymentStatus() {
            return paymentStatus;
        }

        @Override
        public BigDecimal getPaymentAmount() {
            return paymentAmount;
        }

        @Override
        public String getMismatchType() {
            return mismatchType;
        }

        @Override
        public LocalDateTime getOrderUpdatedAt() {
            return orderUpdatedAt;
        }

        @Override
        public LocalDateTime getPaymentUpdatedAt() {
            return paymentUpdatedAt;
        }
    }
}
