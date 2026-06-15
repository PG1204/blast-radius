package com.example.blast_radius.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * Caps the number of analyses in flight at once.
 *
 * <p>Each analysis blocks a servlet thread for up to ~20s on the upstream LLM
 * call, so an unbounded burst would exhaust the thread pool (taking down health
 * checks and everything else) and balloon upstream cost. A bounded permit set
 * sheds load early: when full, callers get an immediate 429 with {@code Retry-After}
 * rather than queueing behind a saturated pool.
 */
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimitFilter.class);

    private static final String RETRY_AFTER_SECONDS = "5";

    private final Semaphore permits;
    private final int maxConcurrent;

    public ConcurrencyLimitFilter(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
        // Fair ordering so requests are admitted roughly first-come-first-served under load.
        this.permits = new Semaphore(maxConcurrent, true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!permits.tryAcquire()) {
            log.warn("Shedding request to {} — at capacity ({} concurrent analyses)",
                    request.getRequestURI(), maxConcurrent);
            response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
            FilterErrorWriter.write(response, 429,
                    "Service at capacity; retry shortly");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
