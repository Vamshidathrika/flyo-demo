package com.flyo.rescue;

import com.flyo.rescue.dto.*;
import com.flyo.rescue.integration.ProviderCapabilityRegistry;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.adapter.AmadeusAlteaAdapter;
import com.flyo.rescue.integration.amadeus.AmadeusGdsSandboxClient;
import com.flyo.rescue.integration.amadeus.AmadeusTicketVerificationResult;
import com.flyo.rescue.integration.dto.TicketVerificationResult;
import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.service.CaseStateMachineService;
import com.flyo.rescue.service.MctRulesService;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class Stage5EnterpriseVerificationAndProviderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RescueCaseRepository caseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RebookOptionRepository optionRepository;

    @Autowired
    private SagaExecutionRepository sagaRepository;

    @Autowired
    private SagaStepLogRepository stepLogRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private RebookingSagaCoordinator sagaCoordinator;

    @Autowired
    private MctRulesService mctRulesService;

    @Autowired
    private ProviderCapabilityRegistry providerRegistry;

    @Autowired
    private AmadeusAlteaAdapter amadeusAlteaAdapter;

    @Autowired
    private PaymentSettlementService paymentSettlementService;

    @MockBean
    private AmadeusGdsSandboxClient amadeusGdsSandboxClient;

    // -------------------------------------------------------------------------
    // TEST 1: AMADEUS GDS CLIENT METADATA & RECLASSIFICATION
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Stage 5.1: Amadeus adapter reports CONFIGURED_NOT_VERIFIED when unconfigured")
    void testAmadeusAdapterReportsHonestStatus() {
        when(amadeusGdsSandboxClient.isConfigured()).thenReturn(false);

        ProviderMetadata meta = amadeusAlteaAdapter.getMetadata();
        assertNotNull(meta);
        assertEquals("amadeus_altea", meta.getProviderId());
        assertEquals(ProviderCapabilityStatus.CONFIGURED_NOT_VERIFIED, meta.getCapabilityStatus());
        assertFalse(meta.isProductionCertified());
    }

    // -------------------------------------------------------------------------
    // TEST 2: INDEPENDENT TICKET VERIFICATION CONTRACT
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Stage 5.2: Independent ticket verification validates coupon status without fake success")
    void testIndependentTicketVerificationContract() {
        when(amadeusGdsSandboxClient.isConfigured()).thenReturn(true);
        when(amadeusGdsSandboxClient.verifyTicket("PNR123", "098-2451928410"))
                .thenReturn(AmadeusTicketVerificationResult.valid("PNR123", "098-2451928410", "OPEN", "Air India"));

        TicketVerificationResult result = amadeusAlteaAdapter.verifyTicket("PNR123", "098-2451928410");
        assertTrue(result.isValid());
        assertEquals("OPEN", result.getCouponStatus());
        assertEquals("098-2451928410", result.getTicketNumber());

        // Test void / failed ticket coupon
        when(amadeusGdsSandboxClient.verifyTicket("PNR123", "098-VOID-TKT"))
                .thenReturn(AmadeusTicketVerificationResult.invalid("PNR123", "098-VOID-TKT", "Coupon marked void"));

        TicketVerificationResult voidResult = amadeusAlteaAdapter.verifyTicket("PNR123", "098-VOID-TKT");
        assertFalse(voidResult.isValid());
        assertEquals("INVALID", voidResult.getCouponStatus());
    }

    // -------------------------------------------------------------------------
    // TEST 3: ANTI-DUPLICATE BOOKING GUARANTEE ON TIMEOUT
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Stage 5.3: Saga coordinator prevents blind retry on network timeout (escalates to manual review)")
    void testAmbiguousOutcomePreventsBlindRetry() {
        String caseId = "CASE-TIMEOUT-" + UUID.randomUUID().toString().substring(0, 6);
        Booking b = new Booking("B-TIMEOUT", "Timeout Pax", "PNRTIME", "HYD-DEL", "AI-839", "Tonight", "None", "None", "Flyo", "Confirmed");
        b.setTenantId("tenant_default");
        bookingRepository.save(b);

        RescueCase c = new RescueCase(caseId, "B-TIMEOUT", "Timeout Pax", CaseStateMachineService.STATE_APPROVED, "HYD-DEL", "AI-839", "None", "None", "None");
        c.setApprovedBy("supervisor_alpha");
        c.setApprovedAt(LocalDateTime.now());
        c.setApprovedOptionId("OPT-TIME");
        caseRepository.save(c);

        RebookOption opt = new RebookOption("OPT-TIME", caseId, "AI-839", "HYD-DEL", "Tonight", "Tomorrow", 120, 0, true, "Direct", 1);
        optionRepository.save(opt);

        // Initiate Saga
        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-TIME", "supervisor_alpha");
        assertNotNull(saga);

        // Manually place saga at STEP_RECONCILE_BOOKING to simulate post-timeout reconciliation
        saga.setCurrentStep(SagaExecution.STEP_RECONCILE_BOOKING);
        saga.setAttemptCount(1);
        sagaRepository.save(saga);

        c.setState(CaseStateMachineService.STATE_RECONCILING);
        caseRepository.save(c);

        // Execute reconciliation step
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-stage5");

        SagaExecution reconciled = sagaRepository.findById(saga.getSagaId()).orElseThrow();
        // MUST escalate to MANUAL_INTERVENTION_REQUIRED rather than scheduling blind duplicate retry
        assertEquals(SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED, reconciled.getStatus(),
                "Must escalate to manual intervention to prevent duplicate bookings");
        assertEquals("UNRESOLVED_AMBIGUITY", reconciled.getFailureReasonCode());

        RescueCase finalCase = caseRepository.findById(caseId).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED, finalCase.getState());
        assertNotNull(finalCase.getManualNextStep());
        assertTrue(finalCase.getManualNextStep().contains("verify airline host") || finalCase.getManualNextStep().contains("carrier host"));
    }

    // -------------------------------------------------------------------------
    // TEST 4: MINIMUM CONNECTION TIME (MCT) HARDENING
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Stage 5.4: MCT evaluation distinguishes published airports from unknown airports requiring manual review")
    void testMctHardeningForUnknownAirports() {
        // Known airport: DEL D-I MCT is 90 mins, Flyo policy is 120 mins
        MctRulesService.MctEvaluation delEval = mctRulesService.evaluate("DEL", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL, 105);
        assertTrue(delEval.isKnownAirportMct());
        assertTrue(delEval.isMeetsPublishedMct());
        assertFalse(delEval.isMeetsFlyoPolicy());
        assertTrue(delEval.isRequiresManualReview());

        // Known airport: 130 mins meets both
        MctRulesService.MctEvaluation delSafe = mctRulesService.evaluate("DEL", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL, 130);
        assertTrue(delSafe.isKnownAirportMct());
        assertTrue(delSafe.isMeetsPublishedMct());
        assertTrue(delSafe.isMeetsFlyoPolicy());
        assertFalse(delSafe.isRequiresManualReview());

        // Unknown airport: "XYZ" not in published matrix
        MctRulesService.MctEvaluation unkEval = mctRulesService.evaluate("XYZ", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL, 150);
        assertFalse(unkEval.isKnownAirportMct());
        assertTrue(unkEval.isRequiresManualReview(), "Unknown airport must flag manual feasibility review");
        assertTrue(unkEval.getSummary().contains("Manual connection feasibility review required"));
    }

    // -------------------------------------------------------------------------
    // TEST 5: PAYMENT LIFECYCLE AUDITABILITY
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Stage 5.5: Payment transaction lifecycle records void and refund states")
    void testPaymentLifecycleStates() {
        String caseId = "CASE-PAY-LC-" + UUID.randomUUID().toString().substring(0, 6);

        PaymentTransaction voidTxn = paymentSettlementService.voidAuthorization(caseId, "VCC-TEST-1", 4500, "Passenger cancelled");
        assertNotNull(voidTxn);
        assertEquals(PaymentTransaction.STATUS_VOIDED, voidTxn.getStatus());
        assertTrue(voidTxn.getId().startsWith("VOID-"));

        PaymentTransaction refundTxn = paymentSettlementService.refundCapturedPayment(caseId, "VCC-TEST-1", 4500, "Airline cancellation");
        assertNotNull(refundTxn);
        assertEquals("REFUNDED", refundTxn.getStatus());
        assertTrue(refundTxn.getId().startsWith("REFUND-"));
    }

    // -------------------------------------------------------------------------
    // TEST 6: FRONTEND PRODUCTION ASSETS VERIFICATION
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Stage 5.6: Frontend production assets bundle and manifest exist")
    void testFrontendProductionAssetsExist() {
        File distDir = new File("flyo-rescue-demo/frontend/dist");
        if (!distDir.exists()) {
            distDir = new File("../frontend/dist");
        }
        assertTrue(distDir.exists() && distDir.isDirectory(), "Frontend dist directory must exist from build");

        File manifestFile = new File(distDir, "manifest.json");
        assertTrue(manifestFile.exists(), "Frontend manifest.json must exist in distribution directory");

        File indexFile = new File(distDir, "index.html");
        assertTrue(indexFile.exists(), "Frontend hashed index.html must exist");
    }
}
