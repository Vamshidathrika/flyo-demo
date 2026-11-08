package com.flyo.rescue.repository;

import com.flyo.rescue.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {
    List<PaymentTransaction> findByCaseIdOrderByTimestampAsc(String caseId);
    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);
    List<PaymentTransaction> findByReconciledFalse();
    List<PaymentTransaction> findByTenantId(String tenantId);
}
