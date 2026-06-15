package com.example.blast_radius.infra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroqClientTest {

    /** Matches MAX_ATTEMPTS in GroqClient. */
    private static final int MAX_ATTEMPTS = 4;

    private static final String VALID_BODY =
            "{\"choices\":[{\"message\":{\"content\":\"{\\\"overallRisk\\\":\\\"LOW\\\"}\"}}]}";
    private static final String EXPECTED_CONTENT = "{\"overallRisk\":\"LOW\"}";

    private final HttpClient httpClient = mock(HttpClient.class);

    /** Client wired with the mock transport and 1ms/2ms backoff so retries don't slow the test. */
    private GroqClient client() {
        return new GroqClient(httpClient, "http://groq.test/v1", "test-key", "test-model", 1L, 2L);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        lenient().when(resp.body()).thenReturn(body);
        lenient().when(resp.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        return resp;
    }

    private HttpResponse<String> ok() {
        return response(200, VALID_BODY, Map.of());
    }

    @Test
    void returnsContent_on2xx() throws Exception {
        HttpResponse<String> ok = ok();
        when(httpClient.<String>send(any(), any())).thenReturn(ok);

        String content = client().callChatApi("prompt");

        assertEquals(EXPECTED_CONTENT, content);
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void retriesOn5xx_thenSucceeds() throws Exception {
        HttpResponse<String> error = response(503, "", Map.of());
        HttpResponse<String> ok = ok();
        when(httpClient.<String>send(any(), any())).thenReturn(error).thenReturn(ok);

        String content = client().callChatApi("prompt");

        assertEquals(EXPECTED_CONTENT, content);
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void retriesOn429_thenSucceeds() throws Exception {
        HttpResponse<String> rateLimited = response(429, "", Map.of());
        HttpResponse<String> ok = ok();
        when(httpClient.<String>send(any(), any())).thenReturn(rateLimited).thenReturn(ok);

        String content = client().callChatApi("prompt");

        assertEquals(EXPECTED_CONTENT, content);
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void honorsRetryAfterHeader_on429() throws Exception {
        // retry-after-ms present — must be parsed and not break the retry path
        HttpResponse<String> rateLimited = response(429, "", Map.of("retry-after-ms", List.of("1")));
        HttpResponse<String> ok = ok();
        when(httpClient.<String>send(any(), any())).thenReturn(rateLimited).thenReturn(ok);

        String content = client().callChatApi("prompt");

        assertEquals(EXPECTED_CONTENT, content);
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void doesNotRetryOn4xx() throws Exception {
        HttpResponse<String> clientError = response(400, "", Map.of());
        when(httpClient.<String>send(any(), any())).thenReturn(clientError);

        GroqApiException ex = assertThrows(GroqApiException.class, () -> client().callChatApi("prompt"));

        assertEquals(400, ex.getHttpStatus());
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void throwsAfterExhausting5xx() throws Exception {
        HttpResponse<String> error = response(503, "", Map.of());
        when(httpClient.<String>send(any(), any())).thenReturn(error);

        GroqApiException ex = assertThrows(GroqApiException.class, () -> client().callChatApi("prompt"));

        assertEquals(503, ex.getHttpStatus());
        verify(httpClient, times(MAX_ATTEMPTS)).send(any(), any());
    }

    @Test
    void retriesOnIOException_thenSucceeds() throws Exception {
        HttpResponse<String> ok = ok();
        when(httpClient.<String>send(any(), any()))
                .thenThrow(new IOException("connection reset"))
                .thenReturn(ok);

        String content = client().callChatApi("prompt");

        assertEquals(EXPECTED_CONTENT, content);
        verify(httpClient, times(2)).send(any(), any());
    }

    @Test
    void throwsAfterExhaustingIOExceptions() throws Exception {
        when(httpClient.<String>send(any(), any())).thenThrow(new IOException("connection reset"));

        GroqApiException ex = assertThrows(GroqApiException.class, () -> client().callChatApi("prompt"));

        verify(httpClient, times(MAX_ATTEMPTS)).send(any(), any());
        assertEquals(-1, ex.getHttpStatus(), "Network-failure exception carries no HTTP status");
    }
}
