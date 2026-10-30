package com.flyo.rescue.controller;

import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.service.FlightAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin(origins = "*")
public class FlightAlertController {

    private final FlightAlertService flightAlertService;

    public FlightAlertController(FlightAlertService flightAlertService) {
        this.flightAlertService = flightAlertService;
    }

    @PostMapping("/flight-status")
    public ResponseEntity<Map<String, Object>> receiveFlightAlert(@RequestBody FlightStatusAlert alert) {
        if (alert == null || alert.getFlight() == null || alert.getFlight().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Flight number is required."
            ));
        }

        try {
            List<RescueCase> affectedCases = flightAlertService.handleFlightStatusAlert(alert);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "flight", alert.getFlight(),
                    "status", alert.getStatus() != null ? alert.getStatus() : "CANCELLED",
                    "affectedCasesCount", affectedCases.size(),
                    "affectedCases", affectedCases,
                    "message", affectedCases.isEmpty()
                            ? "No active monitored bookings were impacted by flight " + alert.getFlight()
                            : String.format("Flight radar alert processed: %d disruption case(s) created and ranked.", affectedCases.size())
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/radar/poll")
    public ResponseEntity<Map<String, Object>> pollRadar() {
        try {
            List<RescueCase> triggeredCases = flightAlertService.pollAllMonitoredFlights();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "polledCasesCount", triggeredCases.size(),
                    "triggeredCases", triggeredCases,
                    "message", triggeredCases.isEmpty()
                            ? "All monitored flights are operating on schedule according to aviation radar."
                            : String.format("Aviation radar detected %d disruption(s) across monitored itineraries.", triggeredCases.size())
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
