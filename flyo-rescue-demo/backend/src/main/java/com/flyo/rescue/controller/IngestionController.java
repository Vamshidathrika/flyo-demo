package com.flyo.rescue.controller;

import com.flyo.rescue.dto.IngestRequest;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.service.PnrIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@CrossOrigin(origins = "*")
public class IngestionController {

    private final PnrIngestionService pnrIngestionService;

    public IngestionController(PnrIngestionService pnrIngestionService) {
        this.pnrIngestionService = pnrIngestionService;
    }

    @PostMapping("/pnr")
    public ResponseEntity<Map<String, Object>> ingestPnr(@RequestBody IngestRequest request) {
        if (request == null || request.getRawText() == null || request.getRawText().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Raw booking text is required."
            ));
        }

        try {
            Booking saved = pnrIngestionService.parseAndIngest(request.getRawText(), request.getSource());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Successfully ingested itinerary for " + saved.getPassengerName() + " (PNR: " + saved.getPnr() + ")",
                    "booking", saved
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
