package com.example.blast_radius.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    @Value("${groq.api.model}")
    private String model;

    public GroqClient() {
        // TODO: Configure timeouts on RestTemplate (e.g., 30s connect, 60s read)
        //   SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        //   factory.setConnectTimeout(Duration.ofSeconds(30));
        //   factory.setReadTimeout(Duration.ofSeconds(60));
        //   this.restTemplate = new RestTemplate(factory);
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends a prompt to the Groq chat completions API and returns the assistant's
     * message content as a raw string.
     */
    public String callChatApi(String promptPayload) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", new Object[]{
                Map.of("role", "user", "content", promptPayload)
        });
        body.put("response_format", Map.of("type", "json_object"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.debug("Calling Groq API at {} with model {}", apiUrl, model);

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl, HttpMethod.POST, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Groq API returned status " + response.getStatusCode());
        }

        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("Groq API returned empty response body");
        }

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");

        if (!choices.isArray() || choices.isEmpty()) {
            throw new RuntimeException("Groq API response contained no choices");
        }

        String content = choices.get(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("Groq API returned empty message content");
        }
        // test comment
        return content;
    }
}
