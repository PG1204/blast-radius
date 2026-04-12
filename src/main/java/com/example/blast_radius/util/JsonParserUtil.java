package com.example.blast_radius.util;

import com.example.blast_radius.model.PrAnalysisResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonParserUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonParserUtil() {
    }

    /**
     * Strips markdown code fences and surrounding text, then parses into PrAnalysisResponse.
     * Handles patterns like: ```json\n{...}\n```, ```\n{...}\n```, or raw JSON.
     */
    public static PrAnalysisResponse toPrAnalysisResponse(String raw) throws Exception {
        String cleaned = stripMarkdownFences(raw);
        return OBJECT_MAPPER.readValue(cleaned, PrAnalysisResponse.class);
    }

    /**
     * Extracts the JSON object from a string that may be wrapped in markdown
     * code fences or contain leading/trailing natural-language text.
     */
    static String stripMarkdownFences(String raw) {
        if (raw == null) {
            return raw;
        }

        String trimmed = raw.strip();

        // Strip ```json ... ``` or ``` ... ``` fences
        if (trimmed.startsWith("```")) {
            // Remove opening fence line (e.g., "```json\n" or "```\n")
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            // Remove closing fence
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
            return trimmed.strip();
        }

        // No fences — try to extract the JSON object between first '{' and last '}'
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        // Nothing to strip; return as-is and let Jackson report the error
        return trimmed;
    }
}
