package com.flyo.rescue.repository;

import com.flyo.rescue.model.SagaStepLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SagaStepLogRepository extends JpaRepository<SagaStepLog, String> {

    List<SagaStepLog> findBySagaIdOrderByCreatedAtAsc(String sagaId);
}
