package com.example.blast_radius.service;

import com.example.blast_radius.infra.GroqClient;
import com.example.blast_radius.model.PrAnalysisRequest;
import com.example.blast_radius.model.PrAnalysisResponse;
import com.example.blast_radius.util.JsonParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    // Max diff size sent to the LLM (32 KB). Larger diffs are truncated.
    private static final int MAX_DIFF_LENGTH = 32_768;

    private final GroqClient groqClient;

    public AnalysisService(GroqClient groqClient) {
        this.groqClient = groqClient;
    }

    public PrAnalysisResponse analyze(PrAnalysisRequest request) {
        if (request.getDiff() == null || request.getDiff().isBlank()) {
            log.warn("Received analysis request with null or blank diff");
            return errorResponse("ERROR");
        }

        try {
            String diff = request.getDiff();

            // Truncate oversized diffs to stay within LLM context limits
            if (diff.length() > MAX_DIFF_LENGTH) {
                log.info("Truncating diff from {} to {} characters", diff.length(), MAX_DIFF_LENGTH);
                diff = diff.substring(0, MAX_DIFF_LENGTH);
            }

            String promptPayload = """
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
                3. Be SPECIFIC:
                   - In impactAreas, mention concrete classes, methods, endpoints, or modules
                     (e.g., "OrderController#createOrder", "PaymentService", "OrderStatus enum").
                   - In suggestedTests, describe targeted tests tied to those areas
                     (e.g., "Add unit test for OrderStatus.SHIPPED serialization in OrderController responses").
                4. Semantics:
                   - LOW: Minor or localized change, unlikely to break core flows.
                   - MEDIUM: Affects important flows but with limited blast radius.
                   - HIGH: Affects critical flows (payments, auth, persistence) or many modules.
                5. Always include AT LEAST 2 impactAreas and 3 suggestedTests when possible.
            
                Now analyze the following Git diff and return ONLY the JSON object:
            
                DIFF:
                """ + diff;

            String rawResponse = groqClient.callChatApi(promptPayload);
            log.debug("Raw LLM response length: {} chars", rawResponse.length());

            try {
                return JsonParserUtil.toPrAnalysisResponse(rawResponse);
            } catch (Exception parseEx) {
                log.warn("Failed to parse LLM response: {}. Raw (first 500 chars): {}",
                        parseEx.getMessage(),
                        rawResponse.substring(0, Math.min(rawResponse.length(), 500)));
                return errorResponse("PARSING_ERROR");
            }

        } catch (Exception e) {
            log.error("Analysis failed: {}", e.getMessage());
            return errorResponse("PARSING_ERROR");
        }
    }

    private PrAnalysisResponse errorResponse(String risk) {
        PrAnalysisResponse response = new PrAnalysisResponse();
        response.setOverallRisk(risk);
        response.setImpactAreas(List.of());
        response.setSuggestedTests(List.of());
        return response;
    }
}
