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

    private static final String PROMPT_VERSION = "v1.0.0";
    private static final String MODEL_NAME = "qwen/qwen3-32b";

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
            response.setPromptVersion(PROMPT_VERSION);
            response.setModelName(MODEL_NAME);
            recordRisk(response.getOverallRisk());
            log.info("[{}] Analysis complete — risk: {}, promptVersion={}, model={}",
                    analysisId, response.getOverallRisk(), PROMPT_VERSION, MODEL_NAME);
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
                  - In impactAreas, mention concrete Spring components that appear in the diff:
                    - @RestController methods (e.g., "OrderController#createOrder").
                    - @Service methods (e.g., "OrderService#updateStatus").
                    - JPA entities/enums (e.g., "Order entity", "OrderStatus enum").
                    - Repository methods (e.g., "OrderRepository#findByStatus").
                    - For DTOs or simple POJOs, name the class and method (e.g., "PrAnalysisRequest#getTargetBranch").
                  - For ENUM changes specifically: you MUST also name the dependent
                    services/controllers/repositories that consume that enum, even if they are
                    not in the diff. Infer the canonical Spring component names from the enum
                    name. For example, a change to OrderStatus MUST include at least one
                    impactAreas entry that mentions OrderService (e.g.,
                    "OrderService flows that depend on OrderStatus values (including the new
                    value)"), plus OrderController and OrderRepository where applicable.
                    For OrderStatus enum changes specifically, ALL THREE of the following
                    impactAreas entries are MANDATORY (each must be a SEPARATE entry, and each
                    must literally contain the specified substring exactly as written):
                      (a) An entry literally containing the substring "OrderService", for
                          example: "OrderService flows that depend on OrderStatus values
                          (including RETURNED)".
                      (b) An entry literally containing the substring
                          "OrderRepository#findByStatus", for example:
                          "OrderRepository#findByStatus queries and similar repository methods
                          that filter by OrderStatus".
                      (c) An entry literally containing the substring "OrderController", for
                          example: "OrderController endpoints that expose or update OrderStatus
                          values (including RETURNED)".
                    Do NOT collapse these into a single combined entry — each anchor
                    (OrderService, OrderRepository#findByStatus, OrderController) must appear in
                    its own distinct impactAreas string. Omitting the OrderService entry is NOT
                    acceptable.
                  - For DTO changes (e.g., PrAnalysisRequest, request/response POJOs): you MUST
                    include at least one generic "any consumer" impactAreas entry whose text
                    literally contains the lowercase word "any". For example:
                    "Any service, controller, or job that consumes PrAnalysisRequest base/target
                    branch information". This anchor is required so downstream consumers that
                    are not visible in the diff are still represented.
                    For changes to a getter like PrAnalysisRequest#getBaseBranch, you MUST ALSO
                    include a SEPARATE impactAreas entry whose text literally contains the
                    fully-qualified getter signature "PrAnalysisRequest#getBaseBranch", for
                    example: "PrAnalysisRequest#getBaseBranch usage in services, controllers,
                    and jobs that depend on base vs target branch". More generally, when a diff
                    modifies a specific getter or method on a DTO, one impactAreas entry must
                    literally contain that ClassName#methodName token.
                  - In suggestedTests, tie tests to those components:
                    - "Add unit test for OrderService#updateStatus covering NEW→SHIPPED and RETURNED flows."
                    - "Add @WebMvcTest for GET /orders/{id} to verify JSON serialization of all OrderStatus values."
                    - "Add persistence test ensuring OrderStatus enum values are stored and read correctly."

               4. Source of truth — ONLY use the diff:
                  - Base your reasoning strictly on the classes, methods, and fields visible in the diff.
                  - Do NOT invent new services, methods, or endpoints that are not mentioned in the diff.
                  - If you need to refer to behavior that is not named explicitly, describe it generically
                    (e.g., "logic that compares baseBranch and targetBranch") instead of fabricating API names.

               5. Risk semantics — classify strictly by these rules:
                  - HIGH:
                    * Logic bugs or behavior changes in core DTOs, services, or shared models that
                      many callers depend on. In particular, ANY case where a getter or field returns
                      the WRONG value, swaps base vs target identifiers, or otherwise changes the
                      meaning of existing data is HIGH (e.g., getBaseBranch() returning targetBranch,
                      getSource() returning destination, swapped from/to fields).
                    * Changes that can silently corrupt data, routing, branch selection, request
                      dispatch, persistence, auth, or payments across many requests — even if the
                      diff is small (a one-line getter swap is HIGH, not LOW).
                    * Changes touching critical flows (payments, auth, persistence) or many modules.
                  - MEDIUM (or higher):
                    * Adding, removing, or renaming enum values used in entities, controllers,
                      repositories, serialization, or external APIs (e.g., adding RETURNED to
                      OrderStatus). These changes have downstream impact on switch statements,
                      JSON contracts, DB columns, and clients — never classify them as LOW.
                    * Changes to important flows with limited blast radius.
                  - LOW:
                    * Purely cosmetic changes: comments, formatting, whitespace, log message
                      wording, javadoc-only edits, or pure renames with no behavior change and
                      no public-API impact. Adding/removing/editing a comment line, tweaking
                      indentation, or changing the wording of a log message MUST be classified
                      as LOW risk regardless of which file it touches.
                    * For LOW-risk cosmetic changes:
                      - impactAreas should be NARROW and scoped to the cosmetic surface only,
                        e.g., "Source comments in BlastRadiusApplication" or "Log message
                        wording in <ClassName>". Do NOT list broad downstream consumers,
                        controllers, services, or repositories — there is no behavioral blast
                        radius to describe.
                      - suggestedTests should be MINIMAL: typically just "Re-run the existing
                        unit and integration test suite to confirm no regressions" or a single
                        smoke test. Do NOT pad with 4+ unrelated tests for cosmetic changes;
                        the ≥4 suggestedTests rule does NOT apply when overallRisk is LOW.

               6. Tie-breaker: When in doubt between two risk levels, ALWAYS choose the HIGHER
                  one — EXCEPT for purely cosmetic changes (comments-only edits, formatting,
                  whitespace, log message wording, javadoc-only edits, pure renames). For those,
                  the correct classification is ALWAYS "LOW", and the tie-breaker MUST NOT
                  escalate them to MEDIUM or HIGH. A diff that touches ONLY comments or
                  whitespace is LOW even if the file it touches is important — the file's
                  importance is irrelevant when no behavior changes. Returning MEDIUM or HIGH
                  for a comment-only diff is INCORRECT.

               7. When the change is more than a trivial refactor, aim for:
                  - AT LEAST 2 impactAreas.
                  - AT LEAST 4 distinct suggestedTests for any change that is NOT clearly LOW
                    risk (this includes ALL enum changes in entities/controllers/repositories,
                    and all DTO/service logic bug fixes). Returning fewer than 4 suggestedTests
                    for such changes is NOT acceptable. The 4 entries MUST mix:
                      * Unit tests (e.g., OrderStatus transitions, repository queries like
                        OrderRepository#findByStatus).
                      * Service-level tests (e.g., OrderService behavior with the new RETURNED
                        status).
                      * Controller/API tests (e.g., OrderController endpoints handling
                        RETURNED, @WebMvcTest for the relevant endpoint).
                      * Integration tests around persistence or serialization where relevant
                        (e.g., EnumType.STRING mapping, JSON serialization across all enum
                        values).
                    Each suggestedTests entry must be distinct — do not duplicate or rephrase
                    the same test.

               Now analyze the following Git diff and return ONLY the JSON object:

               DIFF:
                """ + diff;
    }

    private PrAnalysisResponse recordAndReturn(OverallRisk risk, String analysisId) {
        recordRisk(risk);
        PrAnalysisResponse response = PrAnalysisResponse.error(risk, analysisId);
        response.setPromptVersion(PROMPT_VERSION);
        response.setModelName(MODEL_NAME);
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
