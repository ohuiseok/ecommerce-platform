package com.ecommerce.monolith.outbox.repository;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
