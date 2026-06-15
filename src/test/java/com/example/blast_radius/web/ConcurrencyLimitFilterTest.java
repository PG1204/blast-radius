package com.example.blast_radius.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyLimitFilterTest {

    @Test
    void passesThrough_whenCapacityAvailable() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(2);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis/pr");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};

        filter.doFilter(request, response, (req, res) -> invoked[0] = true);

        assertTrue(invoked[0]);
        assertEquals(200, response.getStatus());
    }

    @Test
    void releasesPermit_soSequentialRequestsAllSucceed() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1);
        AtomicInteger reached = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/analysis/pr"),
                    response, (req, res) -> reached.incrementAndGet());
            assertEquals(200, response.getStatus());
        }

        assertEquals(5, reached.get(), "Permit must be released after each request");
    }

    @Test
    void sheds_whenAtCapacity() throws Exception {
        ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(1);
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        // Thread A grabs the only permit and blocks inside the chain, holding it.
        Thread holder = new Thread(() -> {
            try {
                filter.doFilter(new MockHttpServletRequest("POST", "/analysis/pr"),
                        new MockHttpServletResponse(), (req, res) -> {
                            holderStarted.countDown();
                            try {
                                releaseHolder.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        holder.start();

        assertTrue(holderStarted.await(5, TimeUnit.SECONDS), "Holder should have acquired the permit");

        // Thread B (this thread) should be shed immediately.
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        filter.doFilter(new MockHttpServletRequest("POST", "/analysis/pr"),
                response, (req, res) -> invoked[0] = true);

        assertEquals(429, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("at capacity"));
        org.junit.jupiter.api.Assertions.assertFalse(invoked[0], "Shed request must not reach the chain");

        // Let the holder finish and confirm capacity recovers.
        releaseHolder.countDown();
        holder.join(5_000);

        MockHttpServletResponse recovered = new MockHttpServletResponse();
        boolean[] invokedAfter = {false};
        filter.doFilter(new MockHttpServletRequest("POST", "/analysis/pr"),
                recovered, (req, res) -> invokedAfter[0] = true);
        assertEquals(200, recovered.getStatus());
        assertTrue(invokedAfter[0]);
    }
}
