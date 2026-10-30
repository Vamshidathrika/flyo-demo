package com.flyo.rescue.repository;

import com.flyo.rescue.model.OperatorAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OperatorAccountRepository extends JpaRepository<OperatorAccount, String> {
    Optional<OperatorAccount> findByUsername(String username);
    Optional<OperatorAccount> findByPhoneNumber(String phoneNumber);
}
