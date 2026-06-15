package com.example.blast_radius.web;

import java.io.IOException;

/**
 * Signals that a request body grew past the configured size limit while being read.
 *
 * <p>Thrown by {@link RequestSizeLimitFilter}'s streaming guard for chunked requests
 * (or a lying Content-Length). It extends {@link IOException} so it propagates through
 * the servlet input-stream contract; {@code GlobalExceptionHandler} recognizes it in
 * the cause chain and maps it to HTTP 413, consistent with the Content-Length fast path.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("Request body exceeds limit of " + limit + " bytes");
    }
}
