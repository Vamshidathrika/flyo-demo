package com.flyo.rescue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Service
public class RebookService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RescueCaseRepository caseRepository;

    public RebookService(@Lazy RescueCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight) {
        String passenger = "Ananya Sharma";
        if (caseRepository != null) {
            passenger = caseRepository.findById(caseId).map(RescueCase::getPassengerName).orElse("Ananya Sharma");
        }
        return executePlaywrightRebook(caseId, optionId, flight, passenger);
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight, String passengerName) {
        return executePlaywrightRebook(caseId, optionId, flight, passengerName, null, null, null, 0);
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight, String passengerName,
                                                  String vccCard, String vccExp, String vccCvv, int vccAmount) {
        // Resolve agent working directory
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

        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                "node",
                "--experimental-strip-types",
                "rebook.ts",
                "--caseId", caseId,
                "--optionId", optionId,
                "--flight", flight,
                "--passenger", passenger,
                "--baseUrl", "http://localhost:8080"
        ));
        if (com.flyo.rescue.controller.MockAirlineController.isBroken()) {
            cmd.add("--broken");
        }
        if (vccCard != null && !vccCard.isBlank()) {
            cmd.addAll(java.util.List.of(
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
                        "Playwright subprocess timed out after 30 seconds.",
                        3,
                        java.util.List.of("Worker timed out."),
                        java.util.List.of()
                );
            }

            String jsonOutput = output.toString().trim();
            int jsonStart = jsonOutput.indexOf("{");
            int jsonEnd = jsonOutput.lastIndexOf("}");
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                jsonOutput = jsonOutput.substring(jsonStart, jsonEnd + 1);
            }

            return objectMapper.readValue(jsonOutput, RebookResultDto.class);

        } catch (Exception e) {
            return new RebookResultDto(
                    false,
                    caseId,
                    optionId,
                    flight,
                    null,
                    "Failed to execute Playwright worker: " + e.getMessage(),
                    3,
                    java.util.List.of("Execution error: " + e.getMessage()),
                    java.util.List.of()
            );
        }
    }
}
