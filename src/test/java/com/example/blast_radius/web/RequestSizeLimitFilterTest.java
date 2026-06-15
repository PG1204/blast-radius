package com.example.blast_radius.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSizeLimitFilterTest {

    private static final long LIMIT = 10;

    @Test
    void passesThrough_whenUnderLimit() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(LIMIT);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        request.setContent(new byte[5]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};

        filter.doFilter(request, response, (req, res) -> invoked[0] = true);

        assertTrue(invoked[0], "Chain should be reached for under-limit bodies");
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejects_whenContentLengthExceedsLimit() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(LIMIT);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        request.setContent(new byte[31]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};

        filter.doFilter(request, response, (req, res) -> invoked[0] = true);

        assertFalse(invoked[0], "Chain must not be reached when Content-Length exceeds the limit");
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("exceeds limit"));
    }

    @Test
    void rejects_whenStreamedBodyExceedsLimit_withoutContentLength() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(LIMIT);
        // Simulate a chunked request: a body present but no advertised Content-Length,
        // so only the streaming guard can catch the overflow.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(new byte[30]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain drainingChain = (req, res) -> {
            ServletInputStream in = req.getInputStream();
            byte[] buffer = new byte[8];
            while (in.read(buffer) != -1) {
                // drain until the limiting stream aborts
            }
        };

        filter.doFilter(request, response, drainingChain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("exceeds limit"));
    }
}
