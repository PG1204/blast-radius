package com.example.blast_radius.web;

import com.example.blast_radius.model.OverallRisk;
import com.example.blast_radius.model.PrAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps request-level failures onto the service's graceful-degradation contract.
 *
 * <p>A malformed or empty analysis request body would otherwise produce Spring's
 * default 400 error page; instead it returns HTTP 200 with a
 * {@link PrAnalysisResponse} envelope (overallRisk = {@code PARSING_ERROR}),
 * matching every other failure path so the CI consumer always sees the same shape.
 *
 * <p>The one carve-out: an over-limit body that slipped past the Content-Length
 * fast path (chunked transfer) arrives here wrapping a {@link PayloadTooLargeException}.
 * That is surfaced as 413 — consistent with {@link RequestSizeLimitFilter} — rather
 * than masked as a successful 200 analysis.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableBody(HttpMessageNotReadableException ex) {
        if (hasCause(ex, PayloadTooLargeException.class)) {
            log.warn("Rejected over-limit request body (no Content-Length): {}", rootMessage(ex));
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(Map.of(
                            "error", "Request body exceeds the configured size limit",
                            "status", HttpStatus.CONTENT_TOO_LARGE.value()));
        }

        log.warn("Malformed analysis request body: {}", rootMessage(ex));
        return ResponseEntity.ok(PrAnalysisResponse.error(OverallRisk.PARSING_ERROR, null));
    }

    /** True if {@code type} appears anywhere in the exception's cause chain. */
    private boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The deepest cause's message, for a concise, root-cause log line. */
    private String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
