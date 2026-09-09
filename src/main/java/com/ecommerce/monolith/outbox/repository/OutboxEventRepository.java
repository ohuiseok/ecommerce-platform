package com.ecommerce.monolith.outbox.repository;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAscOutboxEventIdAsc(
            Collection<OutboxEvent.OutboxStatus> statuses,
            LocalDateTime availableAt,
            Pageable pageable
    );
}
