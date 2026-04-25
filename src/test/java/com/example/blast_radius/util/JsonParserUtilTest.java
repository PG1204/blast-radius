package com.example.blast_radius.util;

import com.example.blast_radius.model.OverallRisk;
import com.example.blast_radius.model.PrAnalysisResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonParserUtilTest {

    private static final String VALID_JSON =
            "{\"overallRisk\":\"LOW\",\"impactAreas\":[\"A\"],\"suggestedTests\":[\"T\"]}";

    // ── toPrAnalysisResponse ──────────────────────────────────────────

    @Test
    void toPrAnalysisResponse_parsesPlainJson() throws Exception {
        PrAnalysisResponse response = JsonParserUtil.toPrAnalysisResponse(VALID_JSON);

        assertEquals(OverallRisk.LOW, response.getOverallRisk());
        assertEquals(1, response.getImpactAreas().size());
        assertEquals("A", response.getImpactAreas().get(0));
        assertEquals(1, response.getSuggestedTests().size());
        assertEquals("T", response.getSuggestedTests().get(0));
    }

    @Test
    void toPrAnalysisResponse_parsesJsonInBacktickJsonFence() throws Exception {
        String raw = "```json\n" + VALID_JSON + "\n```";

        PrAnalysisResponse response = JsonParserUtil.toPrAnalysisResponse(raw);

        assertEquals(OverallRisk.LOW, response.getOverallRisk());
        assertEquals("A", response.getImpactAreas().get(0));
        assertEquals("T", response.getSuggestedTests().get(0));
    }

    @Test
    void toPrAnalysisResponse_parsesJsonWithLeadingAndTrailingProse() throws Exception {
        String raw = "Here is your analysis:\n```json\n" + VALID_JSON + "\n```\nThanks!";

        PrAnalysisResponse response = JsonParserUtil.toPrAnalysisResponse(raw);

        assertEquals(OverallRisk.LOW, response.getOverallRisk());
        assertEquals("A", response.getImpactAreas().get(0));
        assertEquals("T", response.getSuggestedTests().get(0));
    }

    @Test
    void toPrAnalysisResponse_throwsOnNonJson() {
        assertThrows(Exception.class,
                () -> JsonParserUtil.toPrAnalysisResponse("not even JSON"));
    }

    // ── stripMarkdownFences ───────────────────────────────────────────

    @Test
    void stripMarkdownFences_handlesPlainJson() {
        String result = JsonParserUtil.stripMarkdownFences(VALID_JSON);
        assertEquals(VALID_JSON, result);
    }

    @Test
    void stripMarkdownFences_handlesBacktickFences() {
        String fenced = "```json\n" + VALID_JSON + "\n```";
        String result = JsonParserUtil.stripMarkdownFences(fenced);
        assertEquals(VALID_JSON, result);
    }

    @Test
    void stripMarkdownFences_extractsJsonFromProse() {
        String prose = "Sure, here you go: " + VALID_JSON + " Hope that helps!";
        String result = JsonParserUtil.stripMarkdownFences(prose);
        assertEquals(VALID_JSON, result);
    }
}
