package com.example.blast_radius.infra;

/**
 * Thrown when the Groq API call fails due to network errors, HTTP errors,
 * or an unparseable response envelope.
 */
public class GroqApiException extends Exception {

    private final int httpStatus;

    public GroqApiException(String message) {
        super(message);
        this.httpStatus = -1;
    }

    public GroqApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public GroqApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
