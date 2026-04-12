package com.example.blast_radius.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical risk levels returned by the analysis pipeline.
 * LOW/MEDIUM/HIGH come from a successful LLM analysis.
 * PARSING_ERROR and ERROR_UPSTREAM represent failure modes.
 */
public enum OverallRisk {

    LOW,
    MEDIUM,
    HIGH,
    PARSING_ERROR,
    ERROR_UPSTREAM;

    @JsonValue
    public String toJson() {
        return name();
    }

    /**
     * Deserializes from the LLM's JSON string.
     * Unrecognized values map to PARSING_ERROR rather than throwing.
     */
    @JsonCreator
    public static OverallRisk fromJson(String value) {
        if (value == null || value.isBlank()) {
            return PARSING_ERROR;
        }
        try {
            return valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PARSING_ERROR;
        }
    }
}
