package com.flyo.rescue.repository;

import com.flyo.rescue.model.PassengerOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PassengerOtpChallengeRepository extends JpaRepository<PassengerOtpChallenge, String> {

    List<PassengerOtpChallenge> findByCaseIdOrderByCreatedAtDesc(String caseId);

    Optional<PassengerOtpChallenge> findTopByCaseIdAndVerifiedFalseOrderByCreatedAtDesc(String caseId);

    Optional<PassengerOtpChallenge> findTopByPassengerPhoneAndCaseIdAndVerifiedFalseOrderByCreatedAtDesc(
            String passengerPhone, String caseId);

    Optional<PassengerOtpChallenge> findTopByPassengerPhoneAndVerifiedFalseOrderByCreatedAtDesc(
            String passengerPhone);
}
