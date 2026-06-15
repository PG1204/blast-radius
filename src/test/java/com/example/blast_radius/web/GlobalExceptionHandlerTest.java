package com.example.blast_radius.web;

import com.example.blast_radius.model.OverallRisk;
import com.example.blast_radius.model.PrAnalysisResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpInputMessage inputMessage = mock(HttpInputMessage.class);

    @Test
    void malformedBody_returns200ParsingErrorEnvelope() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error", inputMessage);

        ResponseEntity<?> response = handler.handleUnreadableBody(ex);

        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(PrAnalysisResponse.class, response.getBody());
        PrAnalysisResponse body = (PrAnalysisResponse) response.getBody();
        assertEquals(OverallRisk.PARSING_ERROR, body.getOverallRisk());
        assertTrue(body.getImpactAreas().isEmpty());
        assertTrue(body.getSuggestedTests().isEmpty());
    }

    @Test
    void overLimitChunkedBody_returns413() {
        // Simulates the streaming size guard tripping during body read.
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "I/O error while reading input", new PayloadTooLargeException(1024), inputMessage);

        ResponseEntity<?> response = handler.handleUnreadableBody(ex);

        assertEquals(413, response.getStatusCode().value());
        assertInstanceOf(Map.class, response.getBody());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(413, body.get("status"));
    }
}
