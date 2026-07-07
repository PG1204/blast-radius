package com.example.blast_radius.web;

import com.example.blast_radius.controller.AnalysisController;
import com.example.blast_radius.model.OverallRisk;
import com.example.blast_radius.model.PrAnalysisResponse;
import com.example.blast_radius.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the controller + GlobalExceptionHandler contract: malformed input
 * yields the always-200 envelope; valid input is delegated to the service.
 */
@WebMvcTest(AnalysisController.class)
class AnalysisControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisService analysisService;

    @Test
    void malformedJson_returns200WithParsingErrorEnvelope() throws Exception {
        mockMvc.perform(post("/analysis/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallRisk").value("PARSING_ERROR"))
                .andExpect(jsonPath("$.impactAreas").isEmpty())
                .andExpect(jsonPath("$.suggestedTests").isEmpty());
    }

    @Test
    void emptyBody_returns200WithParsingErrorEnvelope() throws Exception {
        mockMvc.perform(post("/analysis/pr")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallRisk").value("PARSING_ERROR"));
    }

    @Test
    void validJson_delegatesToService() throws Exception {
        PrAnalysisResponse stub = new PrAnalysisResponse();
        stub.setOverallRisk(OverallRisk.LOW);
        stub.setImpactAreas(List.of("FooController#list"));
        stub.setSuggestedTests(List.of("Test it"));
        when(analysisService.analyze(any())).thenReturn(stub);

        mockMvc.perform(post("/analysis/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseBranch\":\"main\",\"targetBranch\":\"f\",\"diff\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallRisk").value("LOW"))
                .andExpect(jsonPath("$.impactAreas[0]").value("FooController#list"));
    }
}
