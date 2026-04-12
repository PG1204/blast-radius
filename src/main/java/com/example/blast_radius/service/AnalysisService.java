package com.example.blast_radius.service;

import com.example.blast_radius.infra.GroqApiException;
import com.example.blast_radius.infra.GroqClient;
import com.example.blast_radius.model.OverallRisk;
import com.example.blast_radius.model.PrAnalysisRequest;
import com.example.blast_radius.model.PrAnalysisResponse;
import com.example.blast_radius.util.DiffPrioritizer;
import com.example.blast_radius.util.JsonParserUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private static final int MAX_DIFF_LENGTH = 32_768;

    private final GroqClient groqClient;
    private final Counter totalCounter;
    private final MeterRegistry meterRegistry;

    public AnalysisService(GroqClient groqClient, MeterRegistry meterRegistry) {
        this.groqClient = groqClient;
        this.meterRegistry = meterRegistry;
        this.totalCounter = Counter.builder("blast_radius.analyses.total")
                .description("Total number of PR analyses attempted")
                .register(meterRegistry);
    }

    public PrAnalysisResponse analyze(PrAnalysisRequest request) {
        String analysisId = UUID.randomUUID().toString().substring(0, 8);

        if (request.getDiff() == null || request.getDiff().isBlank()) {
            log.warn("[{}] Received analysis request with null or blank diff", analysisId);
            return recordAndReturn(OverallRisk.ERROR_UPSTREAM, analysisId);
        }

        log.info("[{}] Starting analysis — diff length: {} chars", analysisId, request.getDiff().length());

        String diff = prepareDiff(request.getDiff(), analysisId);
        String promptPayload = buildPrompt(diff);

        // --- Call Groq (outer try: network / HTTP errors → ERROR_UPSTREAM) ---
        String rawResponse;
        try {
            rawResponse = groqClient.callChatApi(promptPayload);
        } catch (GroqApiException e) {
            log.error("[{}] Groq API error: {}", analysisId, e.getMessage());
            return recordAndReturn(OverallRisk.ERROR_UPSTREAM, analysisId);
        }

        log.debug("[{}] Raw LLM response length: {} chars", analysisId, rawResponse.length());

        // --- Parse JSON (inner try: bad JSON → PARSING_ERROR) ---
        try {
            PrAnalysisResponse response = JsonParserUtil.toPrAnalysisResponse(rawResponse);
            response.setAnalysisId(analysisId);
            recordRisk(response.getOverallRisk());
            log.info("[{}] Analysis complete — risk: {}", analysisId, response.getOverallRisk());
            return response;
        } catch (Exception parseEx) {
            log.warn("[{}] Failed to parse LLM response: {}. Raw (first 500 chars): {}",
                    analysisId,
                    parseEx.getMessage(),
                    rawResponse.substring(0, Math.min(rawResponse.length(), 500)));
            return recordAndReturn(OverallRisk.PARSING_ERROR, analysisId);
        }
    }

    private String prepareDiff(String diff, String analysisId) {
        if (diff.length() <= MAX_DIFF_LENGTH) {
            return diff;
        }

        // Try smart prioritization first, fall back to raw truncation
        String prioritized = DiffPrioritizer.prioritizeCriticalFiles(diff, MAX_DIFF_LENGTH);
        log.info("[{}] Diff reduced from {} to {} chars via prioritization",
                analysisId, diff.length(), prioritized.length());
        return prioritized;
    }

    private String buildPrompt(String diff) {
        return """
                You are a senior backend engineer reviewing a Git diff in a Java Spring Boot service.
                Your task is to assess RISK and propose TESTS.

                CONTEXT:
                - Tech stack: Java 17, Spring Boot, REST controllers, service layer, JPA repositories.
                - The diff may touch enums, DTOs, controllers, services, or validation logic.

                OUTPUT FORMAT (VERY IMPORTANT):
                1. Output ONLY a single JSON object — no markdown, no code fences, no explanation.
                2. The JSON must have EXACTLY these fields:
                   {
                     "overallRisk": "LOW" | "MEDIUM" | "HIGH",
                     "impactAreas": ["<specific impacted component>"],
                     "suggestedTests": ["<specific test to run>"]
                   }
                3. Be SPECIFIC and SPRING-BOOT AWARE:
                   - In impactAreas, mention concrete Spring components:
                     - @RestController methods (e.g., "OrderController#createOrder").
                     - @Service methods (e.g., "OrderService#updateStatus").
                     - JPA entities/enums (e.g., "Order entity", "OrderStatus enum").
                     - Repository methods (e.g., "OrderRepository#findByStatus").
                   - In suggestedTests, tie tests to those components:
                     - "Add unit test for OrderService#updateStatus covering NEW→SHIPPED and RETURNED flows."
                     - "Add @WebMvcTest for GET /orders/{id} to verify JSON serialization of all OrderStatus values."
                     - "Add persistence test ensuring OrderStatus enum values are stored and read correctly."
                4. Semantics:
                   - LOW: Minor or localized change, unlikely to break core flows.
                   - MEDIUM: Affects important flows but with limited blast radius.
                   - HIGH: Affects critical flows (payments, auth, persistence) or many modules.
                5. When the change is more than a trivial refactor, aim for:
                   - AT LEAST 2 impactAreas.
                   - AT LEAST 3 suggestedTests, mixing unit and Spring tests where relevant.

                Now analyze the following Git diff and return ONLY the JSON object:

                DIFF:
                """ + diff;
    }

    private PrAnalysisResponse recordAndReturn(OverallRisk risk, String analysisId) {
        recordRisk(risk);
        return PrAnalysisResponse.error(risk, analysisId);
    }

    private void recordRisk(OverallRisk risk) {
        totalCounter.increment();
        Counter.builder("blast_radius.analyses.by_risk")
                .tag("risk", risk.name())
                .description("Analyses broken down by risk level")
                .register(meterRegistry)
                .increment();
    }
}
