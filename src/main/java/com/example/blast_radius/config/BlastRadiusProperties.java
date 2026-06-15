package com.example.blast_radius.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational/security knobs for the Blast Radius service, bound from the
 * {@code blast-radius.*} property namespace.
 *
 * <p>All values have safe defaults so the application starts cleanly in dev,
 * test, and CI without extra configuration. Production hardening is opt-in:
 * set {@code blast-radius.api-key} to require authentication.
 */
@ConfigurationProperties(prefix = "blast-radius")
public class BlastRadiusProperties {

    /** Default request-body ceiling: 1 MiB. Well above any diff we actually analyze (truncated to 32 KB). */
    public static final long DEFAULT_MAX_REQUEST_BYTES = 1_048_576L;

    /** Default cap on concurrent in-flight analyses, sized to protect the servlet thread pool and upstream quota. */
    public static final int DEFAULT_MAX_CONCURRENT_ANALYSES = 8;

    /**
     * Shared secret required in the {@code X-API-Key} header (or as a
     * {@code Authorization: Bearer} token) to call analysis endpoints.
     * Blank (the default) disables authentication — intended for local/dev only.
     */
    private String apiKey = "";

    /** Maximum accepted request-body size in bytes; larger requests are rejected with 413. */
    private long maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES;

    /** Maximum number of analyses allowed in flight at once; excess requests get 429. */
    private int maxConcurrentAnalyses = DEFAULT_MAX_CONCURRENT_ANALYSES;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    /** True when an API key is configured and authentication should be enforced. */
    public boolean isAuthEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public int getMaxConcurrentAnalyses() {
        return maxConcurrentAnalyses;
    }

    public void setMaxConcurrentAnalyses(int maxConcurrentAnalyses) {
        this.maxConcurrentAnalyses = maxConcurrentAnalyses;
    }
}
