package com.ecommerce.monolith.payment.client;

import com.ecommerce.monolith.payment.entity.Payment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 실제 PG사 연동 대신 사용하는 모의 결제 클라이언트.
 * 카드 결제는 카드번호 마지막 자리가 홀수이면 승인 거절을 재현해 실패 흐름을 테스트할 수 있게 한다.
 */
@Component
@Profile({"local", "test"})
public class MockPgClient implements PgClient {

    @Override
    public PgResult charge(BigDecimal amount, Payment.PaymentMethod method, String cardNumber) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return PgResult.failure("결제 금액이 유효하지 않습니다");
        }

        if (method == Payment.PaymentMethod.CARD && cardNumber != null && !cardNumber.isBlank()) {
            char lastDigit = cardNumber.charAt(cardNumber.length() - 1);
            if (Character.isDigit(lastDigit) && (lastDigit - '0') % 2 != 0) {
                return PgResult.failure("카드 승인이 거절되었습니다");
            }
        }

        return PgResult.success("MOCK-" + UUID.randomUUID());
    }

    public PgEvent toEvent(Long orderId, BigDecimal amount, PgResult result) {
        if (result.success()) {
            return PgEvent.approved(orderId, result.transactionId(), amount);
        }

        return PgEvent.failed(orderId, amount, result.failureReason());
    }

    public PgEvent cancelEvent(Long orderId, String transactionId, BigDecimal amount) {
        return PgEvent.cancelled(orderId, transactionId, amount);
    }

    public PgEvent duplicateDelivery(PgEvent event) {
        return event.redelivered();
    }

    public enum PgEventType {
        PAYMENT_APPROVED,
        PAYMENT_FAILED,
        PAYMENT_CANCELLED
    }

    public record PgEvent(
            String eventId,
            String deliveryId,
            PgEventType type,
            Long orderId,
            String transactionId,
            BigDecimal amount,
            String failureReason,
            LocalDateTime occurredAt
    ) {

        public PgEvent {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(deliveryId, "deliveryId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(orderId, "orderId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("amount must be greater than zero");
            }

            if ((type == PgEventType.PAYMENT_APPROVED || type == PgEventType.PAYMENT_CANCELLED)
                    && (transactionId == null || transactionId.isBlank())) {
                throw new IllegalArgumentException("transactionId is required for " + type);
            }

            if (type == PgEventType.PAYMENT_FAILED && (failureReason == null || failureReason.isBlank())) {
                throw new IllegalArgumentException("failureReason is required for PAYMENT_FAILED");
            }
        }

        public static PgEvent approved(Long orderId, String transactionId, BigDecimal amount) {
            return new PgEvent(
                    newEventId(),
                    newDeliveryId(),
                    PgEventType.PAYMENT_APPROVED,
                    orderId,
                    transactionId,
                    amount,
                    null,
                    LocalDateTime.now()
            );
        }

        public static PgEvent failed(Long orderId, BigDecimal amount, String failureReason) {
            return new PgEvent(
                    newEventId(),
                    newDeliveryId(),
                    PgEventType.PAYMENT_FAILED,
                    orderId,
                    null,
                    amount,
                    failureReason,
                    LocalDateTime.now()
            );
        }

        public static PgEvent cancelled(Long orderId, String transactionId, BigDecimal amount) {
            return new PgEvent(
                    newEventId(),
                    newDeliveryId(),
                    PgEventType.PAYMENT_CANCELLED,
                    orderId,
                    transactionId,
                    amount,
                    null,
                    LocalDateTime.now()
            );
        }

        public PgEvent redelivered() {
            return new PgEvent(
                    eventId,
                    newDeliveryId(),
                    type,
                    orderId,
                    transactionId,
                    amount,
                    failureReason,
                    occurredAt
            );
        }

        private static String newEventId() {
            return "MOCK-EVENT-" + UUID.randomUUID();
        }

        private static String newDeliveryId() {
            return "MOCK-DELIVERY-" + UUID.randomUUID();
        }
    }
}
