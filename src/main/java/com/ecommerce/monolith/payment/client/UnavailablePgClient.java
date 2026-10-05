package com.ecommerce.monolith.payment.client;

import com.ecommerce.monolith.payment.entity.Payment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Profile("!local & !test")
public class UnavailablePgClient implements PgClient {

    @Override
    public PgResult charge(BigDecimal amount, Payment.PaymentMethod method, String cardNumber) {
        throw new IllegalStateException("운영 PG 클라이언트가 설정되지 않았습니다.");
    }
}
