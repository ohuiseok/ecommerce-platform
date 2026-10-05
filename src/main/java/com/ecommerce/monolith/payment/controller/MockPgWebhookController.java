package com.ecommerce.monolith.payment.controller;

import com.ecommerce.monolith.payment.client.MockPgClient;
import com.ecommerce.monolith.payment.dto.PaymentResponse;
import com.ecommerce.monolith.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/webhooks/mock-pg")
@RequiredArgsConstructor
@Profile({"local", "test"})
public class MockPgWebhookController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse.PaymentWebhookEventInfo> receiveMockPgWebhook(
            @RequestBody MockPgClient.PgEvent event) {
        return ResponseEntity.ok(paymentService.processPaymentWebhookEvent(event));
    }
}
