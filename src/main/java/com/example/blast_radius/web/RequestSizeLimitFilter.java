package com.example.blast_radius.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Rejects request bodies larger than {@code blast-radius.max-request-bytes}.
 *
 * <p>The downstream {@code AnalysisService} only truncates the diff <em>after</em>
 * Spring has fully deserialized the body into memory, so without this guard a
 * single oversized POST could exhaust the heap. Two layers of defense:
 * <ol>
 *   <li><b>Content-Length fast path</b> — well-behaved clients (curl, the CI
 *       workflow, browsers) advertise the length; we reject with 413 before
 *       reading a single byte.</li>
 *   <li><b>Streaming guard</b> — for chunked requests (or a lying Content-Length)
 *       the wrapped input stream aborts the read once the cap is crossed, so the
 *       heap can never grow past the limit even if the header is absent.</li>
 * </ol>
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    private final long maxRequestBytes;

    public RequestSizeLimitFilter(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxRequestBytes) {
            reject(request, response, declaredLength);
            return;
        }

        // Streaming guard for chunked / unknown-length bodies.
        LimitingRequestWrapper wrapped = new LimitingRequestWrapper(request, maxRequestBytes);
        try {
            filterChain.doFilter(wrapped, response);
        } catch (PayloadTooLargeException e) {
            // Reachable only if the wrapped stream's exception is not swallowed by
            // Spring's converter layer (which would otherwise surface it as 400).
            reject(request, response, -1);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long declaredLength)
            throws IOException {
        log.warn("Rejected oversized request to {} (declared length: {}, limit: {} bytes)",
                request.getRequestURI(),
                declaredLength >= 0 ? declaredLength : "unknown",
                maxRequestBytes);
        FilterErrorWriter.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Request body exceeds limit of " + maxRequestBytes + " bytes");
    }

    /** Wraps a request so its body stream is bounded to {@code limit} bytes. */
    private static final class LimitingRequestWrapper extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream cachedStream;
        private BufferedReader cachedReader;

        LimitingRequestWrapper(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (cachedStream == null) {
                cachedStream = new LimitingServletInputStream(super.getInputStream(), limit);
            }
            return cachedStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (cachedReader == null) {
                Charset charset = getCharacterEncoding() != null
                        ? Charset.forName(getCharacterEncoding())
                        : StandardCharsets.UTF_8;
                cachedReader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return cachedReader;
        }
    }

    /** Delegating input stream that throws once more than {@code limit} bytes are read. */
    private static final class LimitingServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        LimitingServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1 && ++count > limit) {
                throw new PayloadTooLargeException(limit);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new PayloadTooLargeException(limit);
                }
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
