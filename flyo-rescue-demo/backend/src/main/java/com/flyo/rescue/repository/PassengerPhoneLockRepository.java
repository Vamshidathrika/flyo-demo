package com.flyo.rescue.repository;

import com.flyo.rescue.model.PassengerPhoneLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PassengerPhoneLockRepository extends JpaRepository<PassengerPhoneLock, String> {

    Optional<PassengerPhoneLock> findByPhoneAndTenantId(String phone, String tenantId);
}
