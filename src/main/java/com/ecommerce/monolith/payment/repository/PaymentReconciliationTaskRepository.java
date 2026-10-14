package com.ecommerce.monolith.payment.repository;

import com.ecommerce.monolith.payment.entity.PaymentReconciliationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentReconciliationTaskRepository extends JpaRepository<PaymentReconciliationTask, Long> {

    Optional<PaymentReconciliationTask> findByPgEventId(String pgEventId);

    Optional<PaymentReconciliationTask> findByPaymentId(Long paymentId);

    List<PaymentReconciliationTask> findByStatusOrderByCreatedAtDesc(
            PaymentReconciliationTask.ReconciliationStatus status
    );
}
