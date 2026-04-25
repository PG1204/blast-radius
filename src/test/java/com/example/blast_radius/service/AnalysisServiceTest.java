package com.example.blast_radius.service;

import com.example.blast_radius.infra.GroqApiException;
import com.example.blast_radius.infra.GroqClient;
import com.example.blast_radius.model.OverallRisk;
import com.example.blast_radius.model.PrAnalysisRequest;
import com.example.blast_radius.model.PrAnalysisResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock
    private GroqClient groqClient;

    private SimpleMeterRegistry meterRegistry;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        analysisService = new AnalysisService(groqClient, meterRegistry);
    }

    // ── Blank diff ────────────────────────────────────────────────────

    @Test
    void analyze_returnsErrorUpstream_whenDiffIsNull() {
        PrAnalysisRequest request = new PrAnalysisRequest();
        request.setDiff(null);

        PrAnalysisResponse response = analysisService.analyze(request);

        assertEquals(OverallRisk.ERROR_UPSTREAM, response.getOverallRisk());
        assertTrue(response.getImpactAreas().isEmpty());
        assertTrue(response.getSuggestedTests().isEmpty());
        assertNotNull(response.getAnalysisId());

        assertTotalCounterIncremented(1);
        assertRiskCounterIncremented("ERROR_UPSTREAM", 1);
    }

    @Test
    void analyze_returnsErrorUpstream_whenDiffIsBlank() {
        PrAnalysisRequest request = new PrAnalysisRequest();
        request.setDiff("   ");

        PrAnalysisResponse response = analysisService.analyze(request);

        assertEquals(OverallRisk.ERROR_UPSTREAM, response.getOverallRisk());
        assertTrue(response.getImpactAreas().isEmpty());
        assertTrue(response.getSuggestedTests().isEmpty());

        assertTotalCounterIncremented(1);
        assertRiskCounterIncremented("ERROR_UPSTREAM", 1);
    }

    // ── Groq API failure ──────────────────────────────────────────────

    @Test
    void analyze_returnsErrorUpstream_whenGroqApiThrows() throws Exception {
        when(groqClient.callChatApi(anyString()))
                .thenThrow(new GroqApiException("connection refused"));

        PrAnalysisRequest request = requestWithDiff("diff --git a/Foo.java b/Foo.java");

        PrAnalysisResponse response = analysisService.analyze(request);

        assertEquals(OverallRisk.ERROR_UPSTREAM, response.getOverallRisk());
        assertTrue(response.getImpactAreas().isEmpty());
        assertTrue(response.getSuggestedTests().isEmpty());

        assertTotalCounterIncremented(1);
        assertRiskCounterIncremented("ERROR_UPSTREAM", 1);
    }

    // ── Malformed JSON ────────────────────────────────────────────────

    @Test
    void analyze_returnsParsingError_whenJsonIsMalformed() throws Exception {
        when(groqClient.callChatApi(anyString()))
                .thenReturn("not json");

        PrAnalysisRequest request = requestWithDiff("diff --git a/Foo.java b/Foo.java");

        PrAnalysisResponse response = analysisService.analyze(request);

        assertEquals(OverallRisk.PARSING_ERROR, response.getOverallRisk());
        assertTrue(response.getImpactAreas().isEmpty());
        assertTrue(response.getSuggestedTests().isEmpty());

        assertTotalCounterIncremented(1);
        assertRiskCounterIncremented("PARSING_ERROR", 1);
    }

    // ── Happy path ────────────────────────────────────────────────────

    @Test
    void analyze_returnsParsedResponse_onHappyPath() throws Exception {
        String validJson = "{\"overallRisk\":\"LOW\","
                + "\"impactAreas\":[\"FooController#list\"],"
                + "\"suggestedTests\":[\"Test FooController\"]}";

        when(groqClient.callChatApi(anyString()))
                .thenReturn(validJson);

        PrAnalysisRequest request = requestWithDiff("diff --git a/Foo.java b/Foo.java");

        PrAnalysisResponse response = analysisService.analyze(request);

        assertEquals(OverallRisk.LOW, response.getOverallRisk());
        assertEquals(1, response.getImpactAreas().size());
        assertEquals("FooController#list", response.getImpactAreas().get(0));
        assertEquals(1, response.getSuggestedTests().size());
        assertEquals("Test FooController", response.getSuggestedTests().get(0));
        assertNotNull(response.getAnalysisId());

        assertTotalCounterIncremented(1);
        assertRiskCounterIncremented("LOW", 1);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private PrAnalysisRequest requestWithDiff(String diff) {
        PrAnalysisRequest req = new PrAnalysisRequest();
        req.setDiff(diff);
        return req;
    }

    private void assertTotalCounterIncremented(double expected) {
        double actual = meterRegistry.counter("blast_radius.analyses.total").count();
        assertEquals(expected, actual,
                "blast_radius.analyses.total should be " + expected);
    }

    private void assertRiskCounterIncremented(String riskTag, double expected) {
        double actual = meterRegistry.counter("blast_radius.analyses.by_risk", "risk", riskTag).count();
        assertEquals(expected, actual,
                "blast_radius.analyses.by_risk{risk=" + riskTag + "} should be " + expected);
    }
}
