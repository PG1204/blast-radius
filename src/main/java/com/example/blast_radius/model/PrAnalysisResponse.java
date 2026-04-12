package com.example.blast_radius.model;

import java.util.List;

public class PrAnalysisResponse {
    private String overallRisk;
    private List<String> impactAreas;
    private List<String> suggestedTests;

    public String getOverallRisk() {
        return overallRisk;
    }

    public void setOverallRisk(String overallRisk) {
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
}
