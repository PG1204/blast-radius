package com.example.blast_radius.config;

import com.example.blast_radius.web.ApiKeyAuthFilter;
import com.example.blast_radius.web.ConcurrencyLimitFilter;
import com.example.blast_radius.web.RequestSizeLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the security/hardening filters that guard the analysis endpoints.
 *
 * <p>Scope is limited to {@code /analysis/*} so actuator health/readiness probes
 * stay open. Order matters: cheap header-only auth runs first (reject unknown
 * callers before any work), then the body-size cap, then the concurrency gate so
 * shed/oversized requests never consume an analysis permit.
 */
@Configuration
@EnableConfigurationProperties(BlastRadiusProperties.class)
public class FilterConfig {

    private static final String ANALYSIS_PATH = "/analysis/*";

    static final int ORDER_AUTH = 10;
    static final int ORDER_SIZE_LIMIT = 20;
    static final int ORDER_CONCURRENCY = 30;

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(BlastRadiusProperties properties) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(properties));
        registration.addUrlPatterns(ANALYSIS_PATH);
        registration.setOrder(ORDER_AUTH);
        registration.setName("apiKeyAuthFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(BlastRadiusProperties properties) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestSizeLimitFilter(properties.getMaxRequestBytes()));
        registration.addUrlPatterns(ANALYSIS_PATH);
        registration.setOrder(ORDER_SIZE_LIMIT);
        registration.setName("requestSizeLimitFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ConcurrencyLimitFilter> concurrencyLimitFilter(BlastRadiusProperties properties) {
        FilterRegistrationBean<ConcurrencyLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ConcurrencyLimitFilter(properties.getMaxConcurrentAnalyses()));
        registration.addUrlPatterns(ANALYSIS_PATH);
        registration.setOrder(ORDER_CONCURRENCY);
        registration.setName("concurrencyLimitFilter");
        return registration;
    }
}
