package com.example.monitoring.common.web;

import com.example.monitoring.auth.web.ApiSecurityErrorWriter;
import com.example.monitoring.common.api.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.Charset;

public class RequestBodySizeLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    private final ApiSecurityErrorWriter errorWriter;

    public RequestBodySizeLimitFilter(ApiSecurityErrorWriter errorWriter) { this.errorWriter = errorWriter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            writeTooLarge(request, response);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        errorWriter.write(request, response,
                new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "요청 본문은 64KiB 이하여야 합니다."));
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) { super(request); }

        @Override public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long readBytes;
                @Override public int read() throws IOException {
                    int value = delegate.read();
                    if (value >= 0) count(1);
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int read = delegate.read(bytes, offset, length);
                    if (read > 0) count(read);
                    return read;
                }
                private void count(int amount) throws PayloadTooLargeException {
                    readBytes += amount;
                    if (readBytes > MAX_BODY_BYTES) throw new PayloadTooLargeException();
                }
                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
            };
        }

        @Override public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
