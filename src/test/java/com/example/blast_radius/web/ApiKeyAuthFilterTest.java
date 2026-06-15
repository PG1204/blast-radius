package com.example.blast_radius.web;

import com.example.blast_radius.config.BlastRadiusProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyAuthFilterTest {

    private static final String SECRET = "s3cr3t-key";

    private BlastRadiusProperties propsWithKey(String key) {
        BlastRadiusProperties props = new BlastRadiusProperties();
        props.setApiKey(key);
        return props;
    }

    /** Records whether the downstream chain was reached. */
    private static final class TrackingChain implements FilterChain {
        boolean invoked = false;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }

    @Test
    void passesThrough_whenAuthDisabled() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(propsWithKey(""));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(request, response, chain);

        assertTrue(chain.invoked, "Chain should be reached when auth is disabled");
        assertEquals(200, response.getStatus());
    }

    @Test
    void passesThrough_withValidKeyInHeader() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(propsWithKey(SECRET));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        request.addHeader("X-API-Key", SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(request, response, chain);

        assertTrue(chain.invoked);
        assertEquals(200, response.getStatus());
    }

    @Test
    void passesThrough_withValidBearerToken() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(propsWithKey(SECRET));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        request.addHeader("Authorization", "Bearer " + SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(request, response, chain);

        assertTrue(chain.invoked);
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejects_whenKeyMissing() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(propsWithKey(SECRET));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(request, response, chain);

        assertFalse(chain.invoked, "Chain must not be reached without a valid key");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Missing or invalid API key"));
    }

    @Test
    void rejects_whenKeyWrong() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(propsWithKey(SECRET));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        request.addHeader("X-API-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackingChain chain = new TrackingChain();

        filter.doFilter(request, response, chain);

        assertFalse(chain.invoked);
        assertEquals(401, response.getStatus());
    }
}
