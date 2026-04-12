package com.example.blast_radius.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private static final int MAX_ATTEMPTS = 2;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    @Value("${groq.api.model}")
    private String model;

    public GroqClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends a prompt to the Groq chat completions API and returns the assistant's
     * message content as a raw string.
     * Retries up to MAX_ATTEMPTS on network errors or 5xx responses.
     * Does not retry on 4xx (client errors).
     */
    public String callChatApi(String promptPayload) throws GroqApiException {
        String requestBody = buildRequestBody(promptPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        IOException lastIoException = null;
        int lastStatusCode = -1;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return extractContent(response.body());
                }

                // 4xx — client error, do not retry
                if (status >= 400 && status < 500) {
                    throw new GroqApiException(
                            "Groq API client error (HTTP " + status + ")", status);
                }

                // 5xx — server error, retry if attempts remain
                lastStatusCode = status;
                log.warn("Groq API returned HTTP {} (attempt {}/{})", status, attempt, MAX_ATTEMPTS);

            } catch (IOException e) {
                lastIoException = e;
                log.warn("Groq API network error on attempt {}/{}: {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GroqApiException("Groq API call interrupted", e);
            }
        }

        // All attempts exhausted
        if (lastIoException != null) {
            throw new GroqApiException(
                    "Groq API failed after " + MAX_ATTEMPTS + " attempts: " + lastIoException.getMessage(),
                    lastIoException);
        }
        throw new GroqApiException(
                "Groq API returned HTTP " + lastStatusCode + " after " + MAX_ATTEMPTS + " attempts",
                lastStatusCode);
    }

    private String buildRequestBody(String prompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", new Object[]{
                            Map.of("role", "user", "content", prompt)
                    },
                    "response_format", Map.of("type", "json_object")
            );
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Groq request body", e);
        }
    }

    private String extractContent(String responseBody) throws GroqApiException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                throw new GroqApiException("Groq API response contained no choices");
            }

            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new GroqApiException("Groq API returned empty message content");
            }

            return content;
        } catch (GroqApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GroqApiException("Failed to parse Groq API response structure", e);
        }
    }
}
