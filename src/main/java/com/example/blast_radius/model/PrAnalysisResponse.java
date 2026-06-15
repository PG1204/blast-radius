package com.example.blast_radius.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class PrAnalysisResponse {

    private OverallRisk overallRisk;
    private List<String> impactAreas;
    private List<String> suggestedTests;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String analysisId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String promptVersion;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String modelName;

    public OverallRisk getOverallRisk() {
        return overallRisk;
    }

    public void setOverallRisk(OverallRisk overallRisk) {
        this.overallRisk = overallRisk;
    }

    public List<String> getImpactAreas() {
        return impactAreas;
    }

    public void setImpactAreas(List<String> impactAreas) {
        this.impactAreas = impactAreas;
    }

    public List<String> getSuggestedTests() {
        return suggestedTests;
    }

    public void setSuggestedTests(List<String> suggestedTests) {
        this.suggestedTests = suggestedTests;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /** Factory for error/failure responses. */
    public static PrAnalysisResponse error(OverallRisk risk, String analysisId) {
        PrAnalysisResponse r = new PrAnalysisResponse();
        r.setOverallRisk(risk);
        r.setImpactAreas(List.of());
        r.setSuggestedTests(List.of());
        r.setAnalysisId(analysisId);
        return r;
    }
}
