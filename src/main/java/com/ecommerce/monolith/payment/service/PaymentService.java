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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationTaskRepository reconciliationTaskRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final OrderService orderService;
    private final MockPgClient mockPgClient;
    private final OutboxEventService outboxEventService;

    public PaymentResponse.PaymentInfo requestPayment(PaymentRequest.Create request) {
        OrderResponse.OrderInfo order = orderService.getOrder(request.getOrderId());
        String idempotencyKey = request.getIdempotencyKey().trim();
        paymentRepository.lockIdempotencyKey(order.getOrderId() + ":" + idempotencyKey);

        return paymentRepository.findByOrderIdAndIdempotencyKey(order.getOrderId(), idempotencyKey)
                .map(payment -> {
                    log.info("event=payment.idempotent_retry orderId={} paymentId={} userId={} status={}",
                            order.getOrderId(), payment.getPaymentId(), payment.getUserId(), payment.getStatus());
                    return PaymentResponse.PaymentInfo.from(payment);
                })
                .orElseGet(() -> createPayment(request, order, idempotencyKey));
    }

    private PaymentResponse.PaymentInfo createPayment(
            PaymentRequest.Create request,
            OrderResponse.OrderInfo order,
            String idempotencyKey
    ) {
        if (paymentRepository.existsByOrderIdAndStatus(order.getOrderId(), Payment.PaymentStatus.COMPLETED)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        if (order.getStatus() != Order.OrderStatus.PENDING) {
            throw new BusinessException(
                    ErrorCode.INVALID_ORDER_STATUS,
                    "결제 대기 상태의 주문만 결제할 수 있습니다: " + order.getStatus()
            );
        }

        Payment payment = Payment.builder()
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .amount(order.getTotalAmount())
                .method(request.getMethod())
                .idempotencyKey(idempotencyKey)
                .build();

        MockPgClient.PgResult result = mockPgClient.charge(payment.getAmount(), payment.getMethod(), request.getCardNumber());
        if (result.success()) {
            payment.complete(result.transactionId());
        } else {
            payment.fail(result.failureReason());
        }

        Payment savedPayment = paymentRepository.save(payment);

        if (savedPayment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            orderService.markOrderConfirmed(order.getOrderId());
            outboxEventService.recordPaymentCompleted(savedPayment);
            log.info("event=payment.completed orderId={} paymentId={} userId={} amount={}",
                    order.getOrderId(), savedPayment.getPaymentId(), savedPayment.getUserId(), savedPayment.getAmount());
        } else {
            orderService.cancelPendingOrderAfterPaymentFailure(order.getOrderId());
            outboxEventService.recordPaymentFailed(savedPayment);
            log.warn("event=payment.failed orderId={} paymentId={} userId={} reason={}",
                    order.getOrderId(), savedPayment.getPaymentId(), savedPayment.getUserId(), savedPayment.getFailureReason());
        }

        return PaymentResponse.PaymentInfo.from(savedPayment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse.PaymentInfo getPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        return PaymentResponse.PaymentInfo.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse.PaymentInfo getPaymentByOrderId(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        return PaymentResponse.PaymentInfo.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse.PaymentOrderMismatchInfo> getPaymentOrderMismatches() {
        return paymentRepository.findPaymentOrderMismatches().stream()
                .map(PaymentResponse.PaymentOrderMismatchInfo::from)
                .collect(Collectors.toList());
    }

    public Optional<PaymentResponse.PaymentReconciliationTaskInfo> registerLateApprovedPaymentForReconciliation(
            MockPgClient.PgEvent event
    ) {
        if (event.type() != MockPgClient.PgEventType.PAYMENT_APPROVED) {
            return Optional.empty();
        }

        OrderResponse.OrderInfo order = orderService.getOrder(event.orderId());
        if (order.getStatus() != Order.OrderStatus.CANCELLED) {
            return Optional.empty();
        }

        Optional<PaymentReconciliationTask> existingTask = reconciliationTaskRepository.findByPgEventId(event.eventId());
        if (existingTask.isPresent()) {
            PaymentReconciliationTask task = existingTask.get();
            log.warn("event=payment.reconciliation_task_registered orderId={} userId={} pgEventId={} pgTransactionId={} amount={} taskId={}",
                    task.getOrderId(), task.getUserId(), task.getPgEventId(), task.getPgTransactionId(), task.getAmount(), task.getTaskId());
            return Optional.of(PaymentResponse.PaymentReconciliationTaskInfo.from(task));
        }

        PaymentReconciliationTask task = reconciliationTaskRepository.save(PaymentReconciliationTask.builder()
                        .type(PaymentReconciliationTask.ReconciliationType.LATE_PAYMENT_APPROVED_AFTER_ORDER_CANCELLED)
                        .orderId(order.getOrderId())
                        .userId(order.getUserId())
                        .pgEventId(event.eventId())
                        .pgDeliveryId(event.deliveryId())
                        .pgTransactionId(event.transactionId())
                        .amount(event.amount())
                        .reason("취소된 주문에 늦은 결제 승인 이벤트가 도착했습니다. PG 환불 또는 수동 보정이 필요합니다.")
                        .pgOccurredAt(event.occurredAt())
                        .build());
        outboxEventService.recordPaymentReconciliationRequired(task);

        log.warn("event=payment.reconciliation_task_registered orderId={} userId={} pgEventId={} pgTransactionId={} amount={} taskId={}",
                task.getOrderId(), task.getUserId(), task.getPgEventId(), task.getPgTransactionId(), task.getAmount(), task.getTaskId());

        return Optional.of(PaymentResponse.PaymentReconciliationTaskInfo.from(task));
    }

    public PaymentResponse.PaymentWebhookEventInfo processPaymentWebhookEvent(MockPgClient.PgEvent event) {
        Optional<PaymentWebhookEvent> existing = webhookEventRepository.findByPgEventId(event.eventId());
        if (existing.isPresent()) {
            PaymentWebhookEvent webhookEvent = existing.get();
            log.info("event=payment.webhook_duplicate pgEventId={} pgDeliveryId={} originalDeliveryId={} status={}",
                    event.eventId(), event.deliveryId(), webhookEvent.getPgDeliveryId(), webhookEvent.getStatus());
            return PaymentResponse.PaymentWebhookEventInfo.from(webhookEvent);
        }

        PaymentWebhookEvent webhookEvent = webhookEventRepository.save(PaymentWebhookEvent.received(event));
        try {
            reflectWebhookEvent(event, webhookEvent);
        } catch (RuntimeException e) {
            webhookEvent.markFailed(e.getMessage());
            log.warn("event=payment.webhook_failed pgEventId={} pgDeliveryId={} orderId={} reason={}",
                    event.eventId(), event.deliveryId(), event.orderId(), e.getMessage());
        }

        PaymentWebhookEvent savedEvent = webhookEventRepository.save(webhookEvent);
        return PaymentResponse.PaymentWebhookEventInfo.from(savedEvent);
    }

    private void reflectWebhookEvent(MockPgClient.PgEvent event, PaymentWebhookEvent webhookEvent) {
        OrderResponse.OrderInfo order = orderService.getOrder(event.orderId());

        if (event.type() == MockPgClient.PgEventType.PAYMENT_APPROVED) {
            reflectApprovedWebhook(event, webhookEvent, order);
            return;
        }

        if (event.type() == MockPgClient.PgEventType.PAYMENT_FAILED) {
            reflectFailedWebhook(event, webhookEvent, order);
            return;
        }

        webhookEvent.markIgnored("현재 단계에서는 결제 취소 웹훅의 후속 상태 변경을 수행하지 않습니다.");
        log.info("event=payment.webhook_ignored pgEventId={} pgDeliveryId={} orderId={} type={}",
                event.eventId(), event.deliveryId(), event.orderId(), event.type());
    }

    private void reflectApprovedWebhook(
            MockPgClient.PgEvent event,
            PaymentWebhookEvent webhookEvent,
            OrderResponse.OrderInfo order
    ) {
        if (order.getStatus() == Order.OrderStatus.PENDING) {
            orderService.markOrderConfirmed(order.getOrderId());
            webhookEvent.markProcessed();
            log.info("event=payment.webhook_approved_processed pgEventId={} pgDeliveryId={} orderId={} userId={}",
                    event.eventId(), event.deliveryId(), order.getOrderId(), order.getUserId());
            return;
        }

        if (order.getStatus() == Order.OrderStatus.CANCELLED) {
            registerLateApprovedPaymentForReconciliation(event);
            webhookEvent.markProcessed();
            return;
        }

        webhookEvent.markIgnored("승인 웹훅을 반영할 수 없는 주문 상태입니다: " + order.getStatus());
    }

    private void reflectFailedWebhook(
            MockPgClient.PgEvent event,
            PaymentWebhookEvent webhookEvent,
            OrderResponse.OrderInfo order
    ) {
        if (order.getStatus() == Order.OrderStatus.PENDING) {
            orderService.cancelPendingOrderAfterPaymentFailure(order.getOrderId());
            webhookEvent.markProcessed();
            log.warn("event=payment.webhook_failed_processed pgEventId={} pgDeliveryId={} orderId={} userId={} reason={}",
                    event.eventId(), event.deliveryId(), order.getOrderId(), order.getUserId(), event.failureReason());
            return;
        }

        webhookEvent.markIgnored("실패 웹훅을 반영할 수 없는 주문 상태입니다: " + order.getStatus());
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse.PaymentReconciliationTaskInfo> getOpenPaymentReconciliationTasks() {
        return reconciliationTaskRepository
                .findByStatusOrderByCreatedAtDesc(PaymentReconciliationTask.ReconciliationStatus.OPEN)
                .stream()
                .map(PaymentResponse.PaymentReconciliationTaskInfo::from)
                .collect(Collectors.toList());
    }

    public PaymentResponse.PaymentInfo cancelPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() != Payment.PaymentStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED, "완료된 결제만 취소할 수 있습니다");
        }

        payment.cancel();
        Payment savedPayment = paymentRepository.save(payment);

        log.info("event=payment.cancelled orderId={} paymentId={} userId={}",
                savedPayment.getOrderId(), savedPayment.getPaymentId(), savedPayment.getUserId());

        return PaymentResponse.PaymentInfo.from(savedPayment);
    }
}
