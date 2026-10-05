package com.ecommerce.monolith.payment.client;

import com.ecommerce.monolith.payment.entity.Payment;

import java.math.BigDecimal;

public interface PgClient {

    PgResult charge(BigDecimal amount, Payment.PaymentMethod method, String cardNumber);

    record PgResult(boolean success, String transactionId, String failureReason) {
        public static PgResult success(String transactionId) {
            return new PgResult(true, transactionId, null);
        }

        public static PgResult failure(String reason) {
            return new PgResult(false, null, reason);
        }
    }
}
