package com.flyo.rescue.service.rebook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resilient Playwright-based Airline Partner Operations Portal Adapter.
 * Development & testing adapter for local headless browser automation.
 * Strictly disabled in production profiles (!prod).
 */
@Component
@Profile("!prod")
public class PartnerPortalPlaywrightAdapter implements FlightBookingProvider {

    private static final Logger log = LoggerFactory.getLogger(PartnerPortalPlaywrightAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderName() {
        return "Flyo Playwright Portal Automation Engine";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public RebookResultDto executeRebook(String caseId, String optionId, String flight, String passengerName,
                                        String vccCard, String vccExp, String vccCvv, int vccAmount) {
        Path agentPath = Paths.get("../agent").toAbsolutePath().normalize();
        if (!agentPath.toFile().exists()) {
            agentPath = Paths.get("flyo-rescue-demo/agent").toAbsolutePath().normalize();
        }
        if (!agentPath.toFile().exists()) {
            agentPath = Paths.get("agent").toAbsolutePath().normalize();
        }

        File agentDir = agentPath.toFile();
        if (!agentDir.exists()) {
            throw new IllegalStateException("Playwright agent directory not found at: " + agentPath);
        }

        String passenger = (passengerName != null && !passengerName.isBlank()) ? passengerName : "Ananya Sharma";

        List<String> cmd = new ArrayList<>(List.of(
                "node",
                "--experimental-strip-types",
                "rebook.ts",
                "--caseId", caseId,
                "--optionId", optionId,
                "--flight", flight,
                "--passenger", passenger,
                "--baseUrl", "http://localhost:8080"
        ));

        if (MockAirlineController.isBroken()) {
            cmd.add("--broken");
        }

        if (vccCard != null && !vccCard.isBlank()) {
            cmd.addAll(List.of(
                    "--vccCard", vccCard,
                    "--vccExp", vccExp != null ? vccExp : "12/28",
                    "--vccCvv", vccCvv != null ? vccCvv : "891",
                    "--vccAmount", String.valueOf(vccAmount)
            ));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(agentDir);
        pb.redirectErrorStream(true);

        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RebookResultDto(
                        false,
                        caseId,
                        optionId,
                        flight,
                        null,
                        "Execution timed out after 30 seconds on airline portal",
                        3,
                        List.of("Execution timed out"),
                        List.of()
                );
            }

            int exitCode = process.exitValue();
            String fullOutput = output.toString();

            int jsonStart = fullOutput.indexOf('{');
            int jsonEnd = fullOutput.lastIndexOf('}');
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                String jsonPart = fullOutput.substring(jsonStart, jsonEnd + 1);
                RebookResultDto result = objectMapper.readValue(jsonPart, RebookResultDto.class);
                return result;
            } else {
                return new RebookResultDto(
                        false,
                        caseId,
                        optionId,
                        flight,
                        null,
                        "Worker exited with code " + exitCode + " without valid JSON: " + fullOutput.trim(),
                        3,
                        List.of("Unexpected worker exit"),
                        List.of()
                );
            }
        } catch (Exception e) {
            log.error("[Playwright Adapter] Rebooking execution error for case {}: {}", caseId, e.getMessage(), e);
            return new RebookResultDto(
                    false,
                    caseId,
                    optionId,
                    flight,
                    null,
                    "Failed to execute Playwright process: " + e.getMessage(),
                    3,
                    List.of("Execution error: " + e.getMessage()),
                    List.of()
            );
        }
    }
}
