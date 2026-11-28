package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.util.Log.warnF;

import jakarta.servlet.DispatcherType;
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
 *
 * <p>Known limits: a rejection made with {@code sendError} (Spring's no-handler 404, a {@code
 * ResponseStatusException}) is not committed by {@code flushBuffer}, so when the body is then cut
 * off that client sees the connection close without a status line; rejections made with {@code
 * setStatus}, as the token filters do, are delivered. A client that sends {@code Expect:
 * 100-continue}, is rejected and then keeps the connection without sending the body holds the
 * worker until it closes or Tomcat's read timeout fires, like any connection that sends nothing. A
 * body that an async handler reads after the valve returned is outside the deadline: the valve
 * leaves a request alone once async processing has started.
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
    // An async or error re-dispatch belongs to a request the first dispatch already handled.
    if (request.getDispatcherType() != DispatcherType.REQUEST
        || !hasBody(contentLength, request.getHeader("Transfer-Encoding"))) {
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
    if (request.isAsyncStarted() || body.finished()) {
      return;
    }
    if (request.getContentLengthLong() >= 0) {
      // Only a body with a Content-Length: for a chunked body "available" counts chunk framing, so
      // it does not promise data and the read could block before the answer is sent.
      try {
        discardBuffered(body);
      } catch (IOException e) {
        // The deadline already passed: the blocking drain below throws it after the answer is sent.
      }
    }
    if (body.finished()) {
      // The whole body had already arrived; there is nothing to wait for and the response keeps
      // the framing it would have had without the valve.
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

  /** Discards what is already buffered, which does not block for a body with a Content-Length. */
  static void discardBuffered(DeadlineInputBuffer body) throws IOException {
    ApplicationBufferHandler handler = new DrainHandler();
    while (!body.finished() && body.available() > 0) {
      if (body.doRead(handler) <= 0) {
        break;
      }
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
