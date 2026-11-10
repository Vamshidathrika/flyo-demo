package com.flyo.rescue.repository;

import com.flyo.rescue.model.AirportMctRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AirportMctRuleRepository extends JpaRepository<AirportMctRule, String> {

    List<AirportMctRule> findByAirportCodeIgnoreCase(String airportCode);

    Optional<AirportMctRule> findByAirportCodeIgnoreCaseAndTransferTypeIgnoreCaseAndApprovalStatus(
            String airportCode, String transferType, String approvalStatus);

    List<AirportMctRule> findByApprovalStatus(String approvalStatus);

    List<AirportMctRule> findByTenantId(String tenantId);
}
