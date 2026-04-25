package com.example.blast_radius.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverallRiskTest {

    @Test
    void fromJson_returnsLow_forLOW() {
        assertEquals(OverallRisk.LOW, OverallRisk.fromJson("LOW"));
    }

    @Test
    void fromJson_returnsMedium_forMEDIUM() {
        assertEquals(OverallRisk.MEDIUM, OverallRisk.fromJson("MEDIUM"));
    }

    @Test
    void fromJson_returnsHigh_forHIGH() {
        assertEquals(OverallRisk.HIGH, OverallRisk.fromJson("HIGH"));
    }

    @Test
    void fromJson_returnsLow_forLowercaseWithSpaces() {
        assertEquals(OverallRisk.LOW, OverallRisk.fromJson(" low "));
    }

    @Test
    void fromJson_returnsHigh_forMixedCaseWithSpaces() {
        assertEquals(OverallRisk.HIGH, OverallRisk.fromJson(" High "));
    }

    @Test
    void fromJson_returnsMedium_forMixedCaseWithSpaces() {
        assertEquals(OverallRisk.MEDIUM, OverallRisk.fromJson(" medium "));
    }

    @Test
    void fromJson_returnsParsingError_forNull() {
        assertEquals(OverallRisk.PARSING_ERROR, OverallRisk.fromJson(null));
    }

    @Test
    void fromJson_returnsParsingError_forEmptyString() {
        assertEquals(OverallRisk.PARSING_ERROR, OverallRisk.fromJson(""));
    }

    @Test
    void fromJson_returnsParsingError_forBlankString() {
        assertEquals(OverallRisk.PARSING_ERROR, OverallRisk.fromJson("   "));
    }

    @Test
    void fromJson_returnsParsingError_forUnknownValue() {
        assertEquals(OverallRisk.PARSING_ERROR, OverallRisk.fromJson("UNKNOWN"));
    }

    @Test
    void fromJson_returnsParsingError_forInvalidFormat() {
        assertEquals(OverallRisk.PARSING_ERROR, OverallRisk.fromJson("low-risk"));
    }
}
