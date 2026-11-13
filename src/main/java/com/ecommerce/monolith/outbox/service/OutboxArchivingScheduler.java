package com.ecommerce.monolith.outbox.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

@Component
@ConditionalOnProperty(name = "app.outbox.archiving.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OutboxArchivingScheduler {
    private final OutboxArchivingService service;

    @Value("${app.outbox.archiving.before-month}")
    private String beforeMonth;

    @Value("${app.outbox.archiving.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.outbox.archiving.fixed-delay-ms:60000}",
            initialDelayString = "${app.outbox.archiving.initial-delay-ms:60000}")
    public void archiveEvents() {
        var boundary = YearMonth.parse(beforeMonth).atDay(1);
        if (boundary.isAfter(LocalDate.now().withDayOfMonth(1))) {
            throw new IllegalArgumentException("미래 월은 아카이브 기준으로 사용할 수 없습니다.");
        }
        service.archiveBatch(boundary.atStartOfDay(), batchSize);
    }
}
