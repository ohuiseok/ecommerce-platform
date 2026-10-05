package com.ecommerce.monolith.payment.client;

import com.ecommerce.monolith.payment.entity.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPgClientTest {

    private final MockPgClient mockPgClient = new MockPgClient();

    @Test
    void toEventCreatesApprovedEventFromSuccessfulChargeResult() {
        PgClient.PgResult chargeResult = PgClient.PgResult.success("MOCK-TX-1");

        MockPgClient.PgEvent event = mockPgClient.toEvent(1L, BigDecimal.valueOf(12000), chargeResult);

        assertThat(event.eventId()).startsWith("MOCK-EVENT-");
        assertThat(event.deliveryId()).startsWith("MOCK-DELIVERY-");
        assertThat(event.type()).isEqualTo(MockPgClient.PgEventType.PAYMENT_APPROVED);
        assertThat(event.orderId()).isEqualTo(1L);
        assertThat(event.transactionId()).isEqualTo("MOCK-TX-1");
        assertThat(event.amount()).isEqualByComparingTo("12000");
        assertThat(event.failureReason()).isNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void toEventCreatesFailedEventFromFailedChargeResult() {
        PgClient.PgResult chargeResult = PgClient.PgResult.failure("카드 승인이 거절되었습니다");

        MockPgClient.PgEvent event = mockPgClient.toEvent(1L, BigDecimal.valueOf(12000), chargeResult);

        assertThat(event.type()).isEqualTo(MockPgClient.PgEventType.PAYMENT_FAILED);
        assertThat(event.orderId()).isEqualTo(1L);
        assertThat(event.transactionId()).isNull();
        assertThat(event.failureReason()).isEqualTo("카드 승인이 거절되었습니다");
    }

    @Test
    void cancelEventCreatesCancelledEvent() {
        MockPgClient.PgEvent event = mockPgClient.cancelEvent(1L, "MOCK-TX-1", BigDecimal.valueOf(12000));

        assertThat(event.type()).isEqualTo(MockPgClient.PgEventType.PAYMENT_CANCELLED);
        assertThat(event.orderId()).isEqualTo(1L);
        assertThat(event.transactionId()).isEqualTo("MOCK-TX-1");
        assertThat(event.amount()).isEqualByComparingTo("12000");
    }

    @Test
    void duplicateDeliveryKeepsEventIdentityButChangesDeliveryIdentity() {
        MockPgClient.PgEvent original = mockPgClient.toEvent(
                1L,
                BigDecimal.valueOf(12000),
                PgClient.PgResult.success("MOCK-TX-1")
        );

        MockPgClient.PgEvent duplicate = mockPgClient.duplicateDelivery(original);

        assertThat(duplicate.eventId()).isEqualTo(original.eventId());
        assertThat(duplicate.deliveryId()).isNotEqualTo(original.deliveryId());
        assertThat(duplicate.type()).isEqualTo(original.type());
        assertThat(duplicate.orderId()).isEqualTo(original.orderId());
        assertThat(duplicate.transactionId()).isEqualTo(original.transactionId());
        assertThat(duplicate.amount()).isEqualByComparingTo(original.amount());
        assertThat(duplicate.occurredAt()).isEqualTo(original.occurredAt());
    }

    @Test
    void approvedAndCancelledEventsRequireTransactionId() {
        assertThatThrownBy(() -> MockPgClient.PgEvent.approved(1L, " ", BigDecimal.valueOf(12000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThatThrownBy(() -> MockPgClient.PgEvent.cancelled(1L, null, BigDecimal.valueOf(12000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");
    }

    @Test
    void failedEventRequiresFailureReason() {
        assertThatThrownBy(() -> MockPgClient.PgEvent.failed(1L, BigDecimal.valueOf(12000), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureReason");
    }

    @Test
    void chargeResultCanStillBeCreatedFromMockCardRule() {
        PgClient.PgResult success = mockPgClient.charge(
                BigDecimal.valueOf(12000),
                Payment.PaymentMethod.CARD,
                "4111111111111112"
        );
        PgClient.PgResult failure = mockPgClient.charge(
                BigDecimal.valueOf(12000),
                Payment.PaymentMethod.CARD,
                "4111111111111111"
        );

        assertThat(success.success()).isTrue();
        assertThat(success.transactionId()).startsWith("MOCK-");
        assertThat(failure.success()).isFalse();
        assertThat(failure.failureReason()).isEqualTo("카드 승인이 거절되었습니다");
    }
}
