package com.example.blast_radius.web;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes a small, uniform JSON error body for requests rejected by servlet
 * filters before they reach the controller (auth, size, concurrency).
 *
 * <p>Deliberately minimal — these are transport-level rejections, not analysis
 * results, so they do NOT use the {@code PrAnalysisResponse} envelope. The body
 * never echoes client input, to avoid reflecting attacker-controlled content.
 */
final class FilterErrorWriter {

    private FilterErrorWriter() {
    }

    /**
     * Commits a JSON error response with the given status and message.
     * No-op if the response is already committed.
     *
     * <p>Does not call {@code response.reset()} on purpose: these are pre-chain
     * rejections (nothing has been written to the body yet), and a reset would
     * also clear headers the caller set first, such as {@code Retry-After}.
     */
    static void write(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"error\":\"" + escape(message) + "\",\"status\":" + status + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    /** Escapes the characters that would break a JSON string literal. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
