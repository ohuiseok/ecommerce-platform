package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.repository.OutboxEventRepository;
import com.ecommerce.monolith.payment.entity.Payment;
import com.ecommerce.monolith.payment.entity.PaymentReconciliationTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void recordOrderCreated(Order order) {
        saveEvent(
                OutboxEvent.EventType.ORDER_CREATED,
                OutboxEvent.AggregateType.ORDER,
                order.getOrderId(),
                orderPayload(OutboxEvent.EventType.ORDER_CREATED, order, null)
        );
    }

    public void recordOrderCancelled(Order order, String reason) {
        saveEvent(
                OutboxEvent.EventType.ORDER_CANCELLED,
                OutboxEvent.AggregateType.ORDER,
                order.getOrderId(),
                orderPayload(OutboxEvent.EventType.ORDER_CANCELLED, order, reason)
        );
    }

    public void recordPaymentCompleted(Payment payment) {
        saveEvent(
                OutboxEvent.EventType.PAYMENT_COMPLETED,
                OutboxEvent.AggregateType.PAYMENT,
                payment.getPaymentId(),
                paymentPayload(OutboxEvent.EventType.PAYMENT_COMPLETED, payment, null)
        );
    }

    public void recordPaymentFailed(Payment payment) {
        saveEvent(
                OutboxEvent.EventType.PAYMENT_FAILED,
                OutboxEvent.AggregateType.PAYMENT,
                payment.getPaymentId(),
                paymentPayload(OutboxEvent.EventType.PAYMENT_FAILED, payment, payment.getFailureReason())
        );
    }

    public void recordPaymentReconciliationRequired(PaymentReconciliationTask task) {
        Map<String, Object> payload = basePayload(
                OutboxEvent.EventType.PAYMENT_RECONCILIATION_REQUIRED,
                OutboxEvent.AggregateType.RECONCILIATION,
                task.getTaskId()
        );
        payload.put("orderId", task.getOrderId());
        payload.put("paymentId", null);
        payload.put("userId", task.getUserId());
        payload.put("amount", task.getAmount());
        payload.put("status", task.getStatus());
        payload.put("reason", task.getReason());
        payload.put("pgEventId", task.getPgEventId());
        payload.put("pgTransactionId", task.getPgTransactionId());

        saveEvent(
                OutboxEvent.EventType.PAYMENT_RECONCILIATION_REQUIRED,
                OutboxEvent.AggregateType.RECONCILIATION,
                task.getTaskId(),
                payload
        );
    }

    private Map<String, Object> orderPayload(OutboxEvent.EventType eventType, Order order, String reason) {
        Map<String, Object> payload = basePayload(eventType, OutboxEvent.AggregateType.ORDER, order.getOrderId());
        payload.put("orderId", order.getOrderId());
        payload.put("paymentId", null);
        payload.put("userId", order.getUserId());
        payload.put("amount", order.getTotalAmount());
        payload.put("status", order.getStatus());
        payload.put("reason", reason);
        return payload;
    }

    private Map<String, Object> paymentPayload(OutboxEvent.EventType eventType, Payment payment, String reason) {
        Map<String, Object> payload = basePayload(eventType, OutboxEvent.AggregateType.PAYMENT, payment.getPaymentId());
        payload.put("orderId", payment.getOrderId());
        payload.put("paymentId", payment.getPaymentId());
        payload.put("userId", payment.getUserId());
        payload.put("amount", payment.getAmount());
        payload.put("status", payment.getStatus());
        payload.put("reason", reason);
        payload.put("pgTransactionId", payment.getPgTransactionId());
        return payload;
    }

    private Map<String, Object> basePayload(
            OutboxEvent.EventType eventType,
            OutboxEvent.AggregateType aggregateType,
            Long aggregateId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("occurredAt", LocalDateTime.now());
        payload.put("aggregateType", aggregateType);
        payload.put("aggregateId", aggregateId);
        return payload;
    }

    private void saveEvent(
            OutboxEvent.EventType eventType,
            OutboxEvent.AggregateType aggregateType,
            Long aggregateId,
            Map<String, Object> payload
    ) {
        UUID eventId = UUID.randomUUID();
        payload.put("eventId", eventId);

        outboxEventRepository.save(OutboxEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .payload(toJson(payload))
                .availableAt(LocalDateTime.now())
                .build());
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 직렬화에 실패했습니다.", e);
        }
    }
}
