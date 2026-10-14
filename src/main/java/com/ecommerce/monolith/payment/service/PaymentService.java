package com.ecommerce.monolith.payment.service;

import com.ecommerce.monolith.common.exception.BusinessException;
import com.ecommerce.monolith.common.exception.ErrorCode;
import com.ecommerce.monolith.order.dto.OrderResponse;
import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.order.service.OrderService;
import com.ecommerce.monolith.outbox.service.OutboxEventService;
import com.ecommerce.monolith.payment.client.MockPgClient;
import com.ecommerce.monolith.payment.client.PgClient;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationTaskRepository reconciliationTaskRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final OrderService orderService;
    private final PgClient pgClient;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;

    public PaymentResponse.PaymentInfo requestPayment(PaymentRequest.Create request) {
        String idempotencyKey = request.getIdempotencyKey().trim();
        PaymentAttempt attempt = transactionTemplate.execute(status -> preparePayment(request, idempotencyKey));
        if (!attempt.requiresPgCharge()) {
            return PaymentResponse.PaymentInfo.from(attempt.payment());
        }

        Payment payment = attempt.payment();
        PgClient.PgResult result = pgClient.charge(
                payment.getAmount(),
                payment.getMethod(),
                request.getCardNumber()
        );

        Payment savedPayment = transactionTemplate.execute(status -> applyPgResult(payment.getPaymentId(), result));
        return PaymentResponse.PaymentInfo.from(savedPayment);
    }

    private PaymentAttempt preparePayment(PaymentRequest.Create request, String idempotencyKey) {
        OrderResponse.OrderInfo order = orderService.getOrderForPaymentUpdate(request.getOrderId());
        paymentRepository.lockIdempotencyKey(order.getOrderId() + ":" + idempotencyKey);

        Optional<Payment> existingPayment =
                paymentRepository.findByOrderIdAndIdempotencyKey(order.getOrderId(), idempotencyKey);
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            log.info("event=payment.idempotent_retry orderId={} paymentId={} userId={} status={}",
                    order.getOrderId(), payment.getPaymentId(), payment.getUserId(), payment.getStatus());
            return PaymentAttempt.existing(payment);
        }

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

        Payment savedPayment = paymentRepository.save(payment);
        return PaymentAttempt.created(savedPayment);
    }

    Payment applyPgResult(Long paymentId, PgClient.PgResult result) {
        Payment savedPayment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (savedPayment.getStatus() != Payment.PaymentStatus.PROCESSING) {
            log.info("event=payment.pg_result_already_applied orderId={} paymentId={} userId={} status={}",
                    savedPayment.getOrderId(), savedPayment.getPaymentId(), savedPayment.getUserId(), savedPayment.getStatus());
            return savedPayment;
        }

        if (result.success()) {
            savedPayment.complete(result.transactionId());
        } else {
            savedPayment.fail(result.failureReason());
        }

        savedPayment = paymentRepository.save(savedPayment);

        if (savedPayment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            orderService.markOrderConfirmed(savedPayment.getOrderId());
            outboxEventService.recordPaymentCompleted(savedPayment);
            log.info("event=payment.completed orderId={} paymentId={} userId={} amount={}",
                    savedPayment.getOrderId(), savedPayment.getPaymentId(), savedPayment.getUserId(), savedPayment.getAmount());
        } else {
            orderService.cancelPendingOrderAfterPaymentFailure(savedPayment.getOrderId());
            outboxEventService.recordPaymentFailed(savedPayment);
            log.warn("event=payment.failed orderId={} paymentId={} userId={} reason={}",
                    savedPayment.getOrderId(), savedPayment.getPaymentId(), savedPayment.getUserId(), savedPayment.getFailureReason());
        }

        return savedPayment;
    }

    private record PaymentAttempt(Payment payment, boolean requiresPgCharge) {

        private static PaymentAttempt created(Payment payment) {
            return new PaymentAttempt(payment, true);
        }

        private static PaymentAttempt existing(Payment payment) {
            return new PaymentAttempt(payment, false);
        }
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

    @Transactional
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

    @Transactional
    public int registerStaleProcessingPaymentsForReconciliation(LocalDateTime cutoff, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("결제 보정 배치 크기는 1 이상이어야 합니다.");
        }

        List<Payment> stalePayments = paymentRepository
                .findByStatusAndCreatedAtLessThanEqualOrderByCreatedAtAscPaymentIdAsc(
                        Payment.PaymentStatus.PROCESSING,
                        cutoff,
                        PageRequest.of(0, batchSize)
                );

        int registeredCount = 0;
        for (Payment payment : stalePayments) {
            if (reconciliationTaskRepository.findByPaymentId(payment.getPaymentId()).isPresent()) {
                log.info("event=payment.processing_timeout_reconciliation_skipped orderId={} paymentId={} userId={} reason=already_registered",
                        payment.getOrderId(), payment.getPaymentId(), payment.getUserId());
                continue;
            }

            PaymentReconciliationTask task = reconciliationTaskRepository.save(PaymentReconciliationTask.builder()
                    .type(PaymentReconciliationTask.ReconciliationType.PG_TIMEOUT_PROCESSING_PAYMENT)
                    .orderId(payment.getOrderId())
                    .userId(payment.getUserId())
                    .paymentId(payment.getPaymentId())
                    .idempotencyKey(payment.getIdempotencyKey())
                    .paymentMethod(payment.getMethod())
                    .amount(payment.getAmount())
                    .reason("PG 응답 타임아웃 또는 결과 불명으로 결제가 PROCESSING 상태에 오래 남았습니다. PG 승인 여부 확인이 필요합니다.")
                    .pgOccurredAt(LocalDateTime.now())
                    .build());
            outboxEventService.recordPaymentReconciliationRequired(task);
            registeredCount++;

            log.warn("event=payment.processing_timeout_reconciliation_registered orderId={} paymentId={} userId={} amount={} taskId={}",
                    task.getOrderId(), task.getPaymentId(), task.getUserId(), task.getAmount(), task.getTaskId());
        }

        return registeredCount;
    }

    @Transactional
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

    @Transactional
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
