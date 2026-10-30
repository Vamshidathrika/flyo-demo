package com.flyo.rescue.repository;

import com.flyo.rescue.model.StateTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StateTransitionRepository extends JpaRepository<StateTransition, Long> {
    List<StateTransition> findByCaseIdOrderByTimestampAsc(String caseId);
}
