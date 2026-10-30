package com.flyo.rescue.controller;

import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.service.PaymentSettlementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "*")
public class PaymentController {

    private final PaymentSettlementService paymentService;

    public PaymentController(PaymentSettlementService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/case/{caseId}")
    public ResponseEntity<Map<String, Object>> getPaymentDetailsForCase(@PathVariable String caseId) {
        Optional<VirtualCard> cardOpt = paymentService.getActiveCardForCase(caseId);
        List<PaymentTransaction> ledger = paymentService.getLedgerForCase(caseId);

        Map<String, Object> response = new HashMap<>();
        response.put("caseId", caseId);
        response.put("hasVirtualCard", cardOpt.isPresent());
        response.put("virtualCard", cardOpt.orElse(null));
        response.put("transactionCount", ledger.size());
        response.put("transactions", ledger);

        return ResponseEntity.ok(response);
    }
}
