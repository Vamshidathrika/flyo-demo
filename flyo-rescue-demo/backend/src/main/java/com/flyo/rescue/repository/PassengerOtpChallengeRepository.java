package com.flyo.rescue.repository;

import com.flyo.rescue.model.PassengerOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PassengerOtpChallengeRepository extends JpaRepository<PassengerOtpChallenge, String> {

    List<PassengerOtpChallenge> findByCaseIdOrderByCreatedAtDesc(String caseId);

    List<PassengerOtpChallenge> findByCaseIdAndInvalidatedFalse(String caseId);

    Optional<PassengerOtpChallenge> findTopByCaseIdAndVerifiedFalseOrderByCreatedAtDesc(String caseId);

    Optional<PassengerOtpChallenge> findTopByCaseIdAndVerifiedFalseAndInvalidatedFalseOrderByCreatedAtDesc(String caseId);

    Optional<PassengerOtpChallenge> findTopByPassengerPhoneAndCaseIdAndVerifiedFalseOrderByCreatedAtDesc(
            String passengerPhone, String caseId);

    Optional<PassengerOtpChallenge> findTopByPassengerPhoneAndCaseIdAndVerifiedFalseAndInvalidatedFalseOrderByCreatedAtDesc(
            String passengerPhone, String caseId);

    Optional<PassengerOtpChallenge> findTopByPassengerPhoneAndVerifiedFalseOrderByCreatedAtDesc(
            String passengerPhone);

    Optional<PassengerOtpChallenge> findTopByPassengerPhoneAndVerifiedFalseAndInvalidatedFalseOrderByCreatedAtDesc(
            String passengerPhone);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE PassengerOtpChallenge c SET c.verified = true, c.verifiedAt = :now, c.invalidated = true, c.invalidatedAt = :now, c.invalidatedReason = 'CONSUMED_SINGLE_USE', c.lockVersion = c.lockVersion + 1 WHERE c.id = :id AND c.verified = false AND c.invalidated = false AND c.attempts < :maxAttempts")
    int atomicConsumeOtp(@org.springframework.data.repository.query.Param("id") String id,
                         @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
                         @org.springframework.data.repository.query.Param("maxAttempts") int maxAttempts);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE PassengerOtpChallenge c SET c.attempts = c.attempts + 1, c.lockVersion = c.lockVersion + 1 WHERE c.id = :id AND c.verified = false AND c.invalidated = false")
    int atomicIncrementAttempts(@org.springframework.data.repository.query.Param("id") String id);
}
