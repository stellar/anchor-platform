package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.util.Log.warnF;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apache.coyote.InputBuffer;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Limits the total time a request body may take to arrive.
 *
 * <p>Tomcat's connection timeout applies to each read, so a sender that sends one byte every few
 * seconds never trips it and holds a worker thread for as long as it likes. This valve wraps the
 * input buffer of the Coyote request, below the servlet API, so that the three ways an application
 * reads a body (the stream, the form parameters, the multipart parts) all fall under one deadline.
 *
 * <p>When the request is finished and its body is not (the application rejected it without reading
 * it, or stopped reading), Tomcat itself reads the rest on the worker thread, with no deadline. The
 * valve therefore answers first, then reads the rest under the deadline, and drops the connection
 * with {@link ActionCode#CLOSE_NOW} when the deadline passes. Throwing out of the valve does not
 * stop Tomcat's own read; the close action does.
 */
public class RequestBodyReadDeadlineValve extends ValveBase {
  /** What the valve reads and discards from an unfinished body; Tomcat's default swallow size. */
  static final long MAX_DRAIN_BYTES = 2L * 1024 * 1024;

  static final int DRAIN_BUFFER_SIZE = 8192;

  private final long timeoutNanos;
  private final LongSupplier ticker;

  public RequestBodyReadDeadlineValve(long timeoutSeconds) {
    this(TimeUnit.SECONDS.toNanos(timeoutSeconds), System::nanoTime);
  }

  RequestBodyReadDeadlineValve(long timeoutNanos, LongSupplier ticker) {
    super(true);
    this.timeoutNanos = timeoutNanos;
    this.ticker = ticker;
  }

  public Duration getTimeout() {
    return Duration.ofNanos(timeoutNanos);
  }

  /** The customizer that adds a valve with the given deadline to the server's Tomcat context. */
  public static WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer(
      long timeoutSeconds) {
    return factory -> factory.addContextValves(new RequestBodyReadDeadlineValve(timeoutSeconds));
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    long contentLength = request.getContentLengthLong();
    if (!hasBody(contentLength, request.getHeader("Transfer-Encoding"))) {
      getNext().invoke(request, response);
      return;
    }

    org.apache.coyote.Request coyoteRequest = request.getCoyoteRequest();
    InputBuffer original = coyoteRequest.getInputBuffer();
    DeadlineInputBuffer body =
        new DeadlineInputBuffer(original, contentLength, timeoutNanos, ticker);
    coyoteRequest.setInputBuffer(body);
    try {
      getNext().invoke(request, response);
    } finally {
      try {
        finishBody(request, response, coyoteRequest, body);
      } finally {
        // Tomcat reuses the processor for the next request; a stale wrapper would cut it at once.
        coyoteRequest.setInputBuffer(original);
      }
    }
  }

  /** A request has a body when it declares a length or a transfer encoding (chunked). */
  static boolean hasBody(long contentLength, String transferEncoding) {
    return contentLength > 0 || (contentLength < 0 && transferEncoding != null);
  }

  private void finishBody(
      Request request,
      Response response,
      org.apache.coyote.Request coyoteRequest,
      DeadlineInputBuffer body) {
    if (body.finished()) {
      return;
    }
    try {
      // The client gets its answer now, not after the rest of its body.
      response.flushBuffer();
    } catch (IOException e) {
      // The client is gone; Tomcat finds out on its own.
      return;
    }
    try {
      drain(body, MAX_DRAIN_BYTES);
    } catch (RequestBodyReadTimeoutException e) {
      warnF(
          "Dropping {} {}: the request body did not arrive within {} ms",
          request.getMethod(),
          request.getRequestURI(),
          TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
      coyoteRequest.action(ActionCode.CLOSE_NOW, e);
    } catch (IOException e) {
      // The client closed the connection while the rest of the body was being read.
    }
  }

  /**
   * Reads and discards the rest of the body through the deadline, up to {@code maxBytes}.
   *
   * @return the number of bytes discarded
   * @throws RequestBodyReadTimeoutException when the deadline passes before the body is finished
   */
  static long drain(DeadlineInputBuffer body, long maxBytes) throws IOException {
    ApplicationBufferHandler handler = new DrainHandler();
    long discarded = 0;
    while (!body.finished() && discarded < maxBytes) {
      int n = body.doRead(handler);
      if (n < 0) {
        break;
      }
      discarded += n;
    }
    return discarded;
  }

  public static class RequestBodyReadTimeoutException extends IOException {
    public RequestBodyReadTimeoutException(long timeoutMillis) {
      super(String.format("The request body did not arrive within %d ms", timeoutMillis));
    }
  }

  /** Delivers the body from the original buffer and throws once the deadline has passed. */
  static class DeadlineInputBuffer implements InputBuffer {
    private final InputBuffer delegate;
    private final long contentLength;
    private final long timeoutNanos;
    private final LongSupplier ticker;
    private final long start;
    private long bytesRead = 0;
    private boolean endOfStream = false;

    DeadlineInputBuffer(
        InputBuffer delegate, long contentLength, long timeoutNanos, LongSupplier ticker) {
      this.delegate = delegate;
      this.contentLength = contentLength;
      this.timeoutNanos = timeoutNanos;
      this.ticker = ticker;
      this.start = ticker.getAsLong();
    }

    @Override
    public int doRead(ApplicationBufferHandler handler) throws IOException {
      checkDeadline();
      int n = delegate.doRead(handler);
      if (n > 0) {
        bytesRead += n;
      } else if (n < 0) {
        endOfStream = true;
      }
      checkDeadline();
      return n;
    }

    @Override
    public int available() {
      return delegate.available();
    }

    /** The body is finished once its declared length was read, or at end of stream. */
    boolean finished() {
      return endOfStream || (contentLength >= 0 && bytesRead >= contentLength);
    }

    private void checkDeadline() throws RequestBodyReadTimeoutException {
      if (!finished() && ticker.getAsLong() - start > timeoutNanos) {
        throw new RequestBodyReadTimeoutException(TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
      }
    }
  }

  private static class DrainHandler implements ApplicationBufferHandler {
    private ByteBuffer buffer = ByteBuffer.allocate(DRAIN_BUFFER_SIZE);

    @Override
    public void setByteBuffer(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public ByteBuffer getByteBuffer() {
      return buffer;
    }

    @Override
    public void expand(int size) {}
  }
}
