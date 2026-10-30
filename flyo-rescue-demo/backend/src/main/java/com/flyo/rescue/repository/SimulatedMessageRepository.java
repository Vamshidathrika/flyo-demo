package com.flyo.rescue.repository;

import com.flyo.rescue.model.SimulatedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulatedMessageRepository extends JpaRepository<SimulatedMessage, Long> {
    List<SimulatedMessage> findByCaseIdOrderByTimestampAsc(String caseId);
}
