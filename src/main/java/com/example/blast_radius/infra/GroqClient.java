package com.example.blast_radius.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    /** HTTP 429 — rate limited. A 4xx, but retryable (unlike other client errors). */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** Base delay for exponential backoff; doubles each attempt, capped at the max. */
    static final long DEFAULT_BASE_BACKOFF_MILLIS = 500L;
    static final long DEFAULT_MAX_BACKOFF_MILLIS = 8_000L;

    static final int DEFAULT_MAX_ATTEMPTS = 4;
    static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L;
    static final long DEFAULT_READ_TIMEOUT_MILLIS = 20_000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;
    private final int maxAttempts;
    private final Duration readTimeout;

    private final String apiKey;
    private final String apiUrl;
    private final String model;

    @Autowired
    public GroqClient(@Value("${groq.api.url}") String apiUrl,
                      @Value("${groq.api.key}") String apiKey,
                      @Value("${groq.api.model}") String model,
                      @Value("${groq.api.connect-timeout-millis:" + DEFAULT_CONNECT_TIMEOUT_MILLIS + "}") long connectTimeoutMillis,
                      @Value("${groq.api.read-timeout-millis:" + DEFAULT_READ_TIMEOUT_MILLIS + "}") long readTimeoutMillis,
                      @Value("${groq.api.max-attempts:" + DEFAULT_MAX_ATTEMPTS + "}") int maxAttempts) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                        .build(),
                apiUrl, apiKey, model,
                DEFAULT_BASE_BACKOFF_MILLIS, DEFAULT_MAX_BACKOFF_MILLIS,
                maxAttempts, Duration.ofMillis(readTimeoutMillis));
    }

    /**
     * Test-only constructor: injects a (mockable) HttpClient and small backoff
     * bounds so retry paths can be exercised without real-time sleeps.
     */
    GroqClient(HttpClient httpClient, String apiUrl, String apiKey, String model,
               long baseBackoffMillis, long maxBackoffMillis) {
        this(httpClient, apiUrl, apiKey, model, baseBackoffMillis, maxBackoffMillis,
                DEFAULT_MAX_ATTEMPTS, Duration.ofMillis(DEFAULT_READ_TIMEOUT_MILLIS));
    }

    private GroqClient(HttpClient httpClient, String apiUrl, String apiKey, String model,
                       long baseBackoffMillis, long maxBackoffMillis,
                       int maxAttempts, Duration readTimeout) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("groq.api.max-attempts must be >= 1, was " + maxAttempts);
        }
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.baseBackoffMillis = baseBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.maxAttempts = maxAttempts;
        this.readTimeout = readTimeout;
    }

    /** The model name this client sends to Groq — the single source of truth for reporting. */
    public String getModel() {
        return model;
    }

    /**
     * Sends a prompt to the Groq chat completions API and returns the assistant's
     * message content as a raw string.
     *
     * <p>Retries up to {@link #maxAttempts} times on network errors, 5xx responses,
     * and 429 (rate limit), with exponential backoff plus jitter between attempts.
     * For 429, honors a {@code Retry-After} / {@code retry-after-ms} header when present.
     * Other 4xx responses are not retried (they are client errors that will not succeed
     * on retry).
     */
    public String callChatApi(String promptPayload) throws GroqApiException {
        String requestBody = buildRequestBody(promptPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        IOException lastIoException = null;
        int lastStatusCode = -1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long delayMillis;
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return extractContent(response.body());
                }

                // 4xx other than 429 — client error, do not retry
                if (status >= 400 && status < 500 && status != HTTP_TOO_MANY_REQUESTS) {
                    throw new GroqApiException(
                            "Groq API client error (HTTP " + status + ")", status);
                }

                // Retryable: 5xx server error or 429 rate limit
                lastStatusCode = status;
                long backoff = backoffMillis(attempt);
                delayMillis = (status == HTTP_TOO_MANY_REQUESTS)
                        ? retryAfterMillis(response).orElse(backoff)
                        : backoff;
                log.warn("Groq API returned HTTP {} (attempt {}/{}); backing off {} ms",
                        status, attempt, maxAttempts, delayMillis);

            } catch (IOException e) {
                lastIoException = e;
                delayMillis = backoffMillis(attempt);
                log.warn("Groq API network error on attempt {}/{}: {}; backing off {} ms",
                        attempt, maxAttempts, e.getMessage(), delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GroqApiException("Groq API call interrupted", e);
            }

            // Back off before the next attempt; skip the sleep after the final attempt.
            if (attempt < maxAttempts) {
                sleep(delayMillis);
            }
        }

        // All attempts exhausted
        if (lastIoException != null) {
            throw new GroqApiException(
                    "Groq API failed after " + maxAttempts + " attempts: " + lastIoException.getMessage(),
                    lastIoException);
        }
        throw new GroqApiException(
                "Groq API returned HTTP " + lastStatusCode + " after " + maxAttempts + " attempts",
                lastStatusCode);
    }

    /**
     * Exponential backoff with equal jitter: the delay for {@code attempt} is a
     * random value in {@code [cap/2, cap]} where {@code cap = min(base * 2^(attempt-1), max)}.
     * Jitter spreads retries so concurrent callers don't stampede a recovering upstream.
     */
    private long backoffMillis(int attempt) {
        long exponential = baseBackoffMillis * (1L << (attempt - 1));
        long cap = Math.min(exponential, maxBackoffMillis);
        long half = cap / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    /**
     * Parses a retry hint from the response. Prefers the OpenAI-style
     * {@code retry-after-ms}, then standard {@code Retry-After} (seconds).
     * HTTP-date forms are ignored (we fall back to backoff). Capped at maxBackoffMillis.
     */
    private OptionalLong retryAfterMillis(HttpResponse<String> response) {
        Optional<String> millis = response.headers().firstValue("retry-after-ms");
        if (millis.isPresent()) {
            OptionalLong parsed = parsePositiveLong(millis.get());
            if (parsed.isPresent()) {
                return OptionalLong.of(Math.min(parsed.getAsLong(), maxBackoffMillis));
            }
        }
        Optional<String> seconds = response.headers().firstValue("retry-after");
        if (seconds.isPresent()) {
            OptionalLong parsed = parsePositiveLong(seconds.get());
            if (parsed.isPresent()) {
                return OptionalLong.of(Math.min(parsed.getAsLong() * 1000L, maxBackoffMillis));
            }
        }
        return OptionalLong.empty();
    }

    private OptionalLong parsePositiveLong(String raw) {
        try {
            long value = Long.parseLong(raw.strip());
            return value >= 0 ? OptionalLong.of(value) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private void sleep(long millis) throws GroqApiException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GroqApiException("Groq API retry backoff interrupted", e);
        }
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
