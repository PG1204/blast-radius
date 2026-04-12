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
                    You are a code-change risk analyst. Analyze the diff below and produce a JSON assessment.

                    RULES:
                    1. Output ONLY a single JSON object — no markdown, no code fences, no explanation.
                    2. The JSON must match this exact schema:
                       {
                         "overallRisk": "LOW" | "MEDIUM" | "HIGH",
                         "impactAreas": ["<affected module or component>"],
                         "suggestedTests": ["<test description>"]
                       }
                    3. Do NOT add any fields beyond overallRisk, impactAreas, and suggestedTests.
                    4. impactAreas and suggestedTests must each contain at least one entry.

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
