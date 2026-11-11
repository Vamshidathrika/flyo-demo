package com.flyo.rescue.repository;

import com.flyo.rescue.model.RebookOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RebookOptionRepository extends JpaRepository<RebookOption, String> {
    List<RebookOption> findByCaseIdOrderByRankAsc(String caseId);
    void deleteByCaseId(String caseId);
}
