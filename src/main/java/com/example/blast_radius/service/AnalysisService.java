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
    private final PromptTemplate promptTemplate;
    private final Counter totalCounter;
    private final MeterRegistry meterRegistry;

    public AnalysisService(GroqClient groqClient, PromptTemplate promptTemplate,
                           MeterRegistry meterRegistry) {
        this.groqClient = groqClient;
        this.promptTemplate = promptTemplate;
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
        String promptPayload = promptTemplate.render(
                request.getBaseBranch(), request.getTargetBranch(), diff);

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
            response.setPromptVersion(promptTemplate.getVersion());
            response.setModelName(groqClient.getModel());
            recordRisk(response.getOverallRisk());
            log.info("[{}] Analysis complete — risk: {}, promptVersion={}, model={}",
                    analysisId, response.getOverallRisk(), promptTemplate.getVersion(), groqClient.getModel());
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

    private PrAnalysisResponse recordAndReturn(OverallRisk risk, String analysisId) {
        recordRisk(risk);
        PrAnalysisResponse response = PrAnalysisResponse.error(risk, analysisId);
        response.setPromptVersion(promptTemplate.getVersion());
        response.setModelName(groqClient.getModel());
        return response;
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
