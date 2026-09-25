package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.util.Log.warnF;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestBodySizeLimitFilter extends OncePerRequestFilter {
  static final String TOO_LARGE_BODY = "{\"error\":\"The request body is too large.\"}";

  private final long maxBodySize;

  public RequestBodySizeLimitFilter(long maxBodySize) {
    this.maxBodySize = maxBodySize;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxBodySize) {
      warnF(
          "Rejecting {} {}: Content-Length {} exceeds the limit of {} bytes",
          request.getMethod(),
          request.getRequestURI(),
          contentLength,
          maxBodySize);
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType("application/json");
      response.getWriter().write(TOO_LARGE_BODY);
      return;
    }
    filterChain.doFilter(new LimitedRequest(request, maxBodySize), response);
  }

  public static class RequestBodyTooLargeException extends IOException {
    public RequestBodyTooLargeException(long maxBodySize) {
      super(String.format("The request body exceeds the limit of %d bytes", maxBodySize));
    }
  }

  static class LimitedRequest extends HttpServletRequestWrapper {
    private final long maxBodySize;
    private ServletInputStream inputStream;

    LimitedRequest(HttpServletRequest request, long maxBodySize) {
      super(request);
      this.maxBodySize = maxBodySize;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (inputStream == null) {
        inputStream = new LimitedInputStream(super.getInputStream(), maxBodySize);
      }
      return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  public static class LimitedInputStream extends ServletInputStream {
    private final ServletInputStream delegate;
    private final long maxBodySize;
    private long bytesRead = 0;

    public LimitedInputStream(ServletInputStream delegate, long maxBodySize) {
      this.delegate = delegate;
      this.maxBodySize = maxBodySize;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b != -1) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(@NotNull byte[] buffer, int offset, int length) throws IOException {
      int n = delegate.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(long n) throws IOException {
      bytesRead += n;
      if (bytesRead > maxBodySize) {
        throw new RequestBodyTooLargeException(maxBodySize);
      }
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
