package com.flyo.rescue.repository;

import com.flyo.rescue.model.VirtualCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VirtualCardRepository extends JpaRepository<VirtualCard, String> {
    Optional<VirtualCard> findFirstByCaseIdOrderByIssuedAtDesc(String caseId);
    List<VirtualCard> findByCaseIdOrderByIssuedAtDesc(String caseId);
}
