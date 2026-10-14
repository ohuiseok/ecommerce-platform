package com.ecommerce.monolith.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessingPaymentReconciliationScheduler {

    private final PaymentService paymentService;

    @Value("${app.payment.processing-reconciliation.timeout-minutes:10}")
    private long timeoutMinutes;

    @Value("${app.payment.processing-reconciliation.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.payment.processing-reconciliation.fixed-delay-ms:60000}",
            initialDelayString = "${app.payment.processing-reconciliation.initial-delay-ms:60000}"
    )
    public void registerStaleProcessingPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int registeredCount = paymentService.registerStaleProcessingPaymentsForReconciliation(cutoff, batchSize);

        if (registeredCount > 0) {
            log.warn("event=payment.processing_reconciliation_completed registeredCount={} cutoff={}",
                    registeredCount, cutoff);
        }
    }
}
