package com.example.blast_radius.web;

import com.example.blast_radius.config.BlastRadiusProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires a shared secret on guarded endpoints. The secret may be supplied via
 * the {@code X-API-Key} header or an {@code Authorization: Bearer <key>} header.
 *
 * <p>Enforcement is opt-in: if no {@code blast-radius.api-key} is configured the
 * filter passes everything through (and warns once at startup). When a key IS
 * configured, requests without a matching key are rejected with 401 before any
 * work — protecting the paid upstream LLM from unauthenticated callers.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final BlastRadiusProperties properties;

    public ApiKeyAuthFilter(BlastRadiusProperties properties) {
        this.properties = properties;
        if (!properties.isAuthEnabled()) {
            log.warn("blast-radius.api-key is not set — analysis endpoints are UNAUTHENTICATED. "
                    + "Set BLAST_RADIUS_API_KEY in any non-local environment.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!properties.isAuthEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String presented = extractKey(request);
        if (presented == null || !constantTimeEquals(presented, properties.getApiKey())) {
            log.warn("Rejected unauthenticated request to {} from {}",
                    request.getRequestURI(), request.getRemoteAddr());
            FilterErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or invalid API key");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** Pulls the key from X-API-Key, falling back to an Authorization: Bearer header. */
    private String extractKey(HttpServletRequest request) {
        String headerKey = request.getHeader(API_KEY_HEADER);
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.strip();
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).strip();
            return token.isBlank() ? null : token;
        }
        return null;
    }

    /** Length-independent, content constant-time comparison to avoid timing side channels. */
    private boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
