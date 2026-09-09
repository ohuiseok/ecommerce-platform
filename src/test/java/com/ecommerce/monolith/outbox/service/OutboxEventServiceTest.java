package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.order.entity.Order;
import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.repository.OutboxEventRepository;
import com.ecommerce.monolith.payment.entity.Payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    void recordOrderCreatedStoresPendingOutboxEventWithMinimalPayload() throws Exception {
        OutboxEventService service = new OutboxEventService(outboxEventRepository, objectMapper());
        Order order = Order.builder()
                .orderId(1L)
                .userId(10L)
                .totalAmount(BigDecimal.valueOf(12000))
                .status(Order.OrderStatus.PENDING)
                .build();
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordOrderCreated(order);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        Map<String, Object> payload = readPayload(event.getPayload());

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventType()).isEqualTo(OutboxEvent.EventType.ORDER_CREATED);
        assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.AggregateType.ORDER);
        assertThat(event.getAggregateId()).isEqualTo(1L);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getAvailableAt()).isNotNull();
        assertThat(payload).containsEntry("orderId", 1);
        assertThat(payload).containsEntry("paymentId", null);
        assertThat(payload).containsEntry("userId", 10);
        assertThat(payload).containsEntry("status", "PENDING");
        assertThat(payload).containsKey("eventId");
        assertThat(payload).doesNotContainKeys("shippingAddress", "recipientPhone", "cardNumber");
    }

    @Test
    void recordPaymentFailedStoresFailureReasonInPayload() throws Exception {
        OutboxEventService service = new OutboxEventService(outboxEventRepository, objectMapper());
        Payment payment = Payment.builder()
                .paymentId(2L)
                .orderId(1L)
                .userId(10L)
                .amount(BigDecimal.valueOf(12000))
                .method(Payment.PaymentMethod.CARD)
                .status(Payment.PaymentStatus.FAILED)
                .failureReason("카드 승인이 거절되었습니다")
                .build();
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordPaymentFailed(payment);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        Map<String, Object> payload = readPayload(event.getPayload());

        assertThat(event.getEventType()).isEqualTo(OutboxEvent.EventType.PAYMENT_FAILED);
        assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.AggregateType.PAYMENT);
        assertThat(event.getAggregateId()).isEqualTo(2L);
        assertThat(payload).containsEntry("orderId", 1);
        assertThat(payload).containsEntry("paymentId", 2);
        assertThat(payload).containsEntry("reason", "카드 승인이 거절되었습니다");
        assertThat(payload).doesNotContainKey("cardNumber");
    }

    @Test
    void findPublishableEventsReturnsPendingAndFailedEventsAvailableNow() {
        OutboxEventService service = new OutboxEventService(outboxEventRepository, objectMapper());
        OutboxEvent event = outboxEvent(1L, OutboxEvent.OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        when(outboxEventRepository.findByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAscOutboxEventIdAsc(
                anyCollection(),
                any(LocalDateTime.class),
                any()
        )).thenReturn(List.of(event));

        List<OutboxEvent> events = service.findPublishableEvents(10);

        assertThat(events).containsExactly(event);
        verify(outboxEventRepository).findByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAscOutboxEventIdAsc(
                eq(List.of(OutboxEvent.OutboxStatus.PENDING, OutboxEvent.OutboxStatus.FAILED)),
                any(LocalDateTime.class),
                any()
        );
    }

    @Test
    void findPublishableEventsRejectsInvalidBatchSize() {
        OutboxEventService service = new OutboxEventService(outboxEventRepository, objectMapper());

        assertThatThrownBy(() -> service.findPublishableEvents(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Outbox 발행 배치 크기는 1 이상이어야 합니다.");
    }

    @Test
    void markPublishedChangesStatusAndClearsLastError() {
        OutboxEventService service = new OutboxEventService(outboxEventRepository, objectMapper());
        OutboxEvent event = outboxEvent(1L, OutboxEvent.OutboxStatus.FAILED, LocalDateTime.now().minusMinutes(1));
        event.setLastError("temporary failure");
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));

        service.markPublished(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private Map<String, Object> readPayload(String payload) throws Exception {
        return objectMapper().readValue(payload, Map.class);
    }

    private OutboxEvent outboxEvent(Long outboxEventId, OutboxEvent.OutboxStatus status, LocalDateTime availableAt) {
        return OutboxEvent.builder()
                .outboxEventId(outboxEventId)
                .eventId(UUID.randomUUID())
                .eventType(OutboxEvent.EventType.ORDER_CREATED)
                .aggregateType(OutboxEvent.AggregateType.ORDER)
                .aggregateId(1L)
                .payload("{}")
                .status(status)
                .availableAt(availableAt)
                .build();
    }
}
