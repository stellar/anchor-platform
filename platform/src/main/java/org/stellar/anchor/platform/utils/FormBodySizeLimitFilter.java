package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.util.Log.warnF;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.filter.FormContentFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds the body of a form-encoded {@code PUT}, {@code PATCH} or {@code DELETE} before Spring's
 * {@link FormContentFilter} reads it.
 *
 * <p>That filter copies the whole body into a string, splits it into every pair and builds a map of
 * them, with no limit and before any authentication. The result is about 30 times the size of the
 * body in live heap, so a general body cap alone is not enough: the cap times 30 times the number
 * of workers must stay under the heap. This filter applies a small cap to exactly the requests the
 * form filter parses, and leaves every other request untouched. The form filter itself stays
 * enabled because {@code DELETE /sep12/customer/{account}} reads its parameters through it.
 */
public class FormBodySizeLimitFilter extends OncePerRequestFilter {
  static final String TOO_LARGE_BODY = "{\"error\":\"The request body is too large.\"}";
  private static final Set<String> PARSED_METHODS = Set.of("PUT", "PATCH", "DELETE");
  private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

  private final long maxBodySize;

  public FormBodySizeLimitFilter(long maxBodySize) {
    this.maxBodySize = maxBodySize;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    if (!isParsedAsForm(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    long contentLength = request.getContentLengthLong();
    if (contentLength > maxBodySize) {
      warnF(
          "Rejecting {} {}: form body Content-Length {} exceeds the limit of {} bytes",
          request.getMethod(),
          request.getRequestURI(),
          contentLength,
          maxBodySize);
      reject(response);
      return;
    }

    try {
      // A body without Content-Length is counted as the form filter reads it.
      filterChain.doFilter(
          new RequestBodySizeLimitFilter.LimitedRequest(request, maxBodySize), response);
    } catch (IOException | ServletException e) {
      if (!isBodyTooLarge(e) || response.isCommitted()) {
        throw e;
      }
      warnF(
          "Rejecting {} {}: form body exceeds the limit of {} bytes",
          request.getMethod(),
          request.getRequestURI(),
          maxBodySize);
      response.resetBuffer();
      reject(response);
    }
  }

  /** The requests {@link FormContentFilter} parses: a form content type on PUT, PATCH or DELETE. */
  static boolean isParsedAsForm(HttpServletRequest request) {
    String method = request.getMethod();
    String contentType = request.getContentType();
    if (method == null || contentType == null) {
      return false;
    }
    if (!PARSED_METHODS.contains(method.toUpperCase())) {
      return false;
    }
    int parameters = contentType.indexOf(';');
    String mediaType = (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim();
    return FORM_CONTENT_TYPE.equalsIgnoreCase(mediaType);
  }

  private static boolean isBodyTooLarge(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof RequestBodySizeLimitFilter.RequestBodyTooLargeException) {
        return true;
      }
    }
    return false;
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    response.setContentType("application/json");
    response.getWriter().write(TOO_LARGE_BODY);
  }
}
