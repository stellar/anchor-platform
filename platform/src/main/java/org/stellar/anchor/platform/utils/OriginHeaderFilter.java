package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.util.Log.warnF;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request whose {@code Origin} header is not a real origin, before Spring parses it.
 *
 * <p>For a handler annotated {@code @CrossOrigin}, Spring MVC matches the header against a
 * backtracking pattern ({@code UriComponentsBuilder.fromOriginHeader}) before the handler runs, and
 * the cost grows with the cube of the header length on every Spring 6.1 release. No credential is
 * involved, so one unauthenticated client can hold every request thread. A real origin is the
 * literal {@code null} or {@code scheme://host[:port]}; anything else is answered 400 here.
 *
 * <p>The check does not depend on the Spring version, so it stays correct after a move to a release
 * that parses the header in linear time.
 */
public class OriginHeaderFilter extends OncePerRequestFilter {
  static final String INVALID_ORIGIN = "{\"error\":\"Invalid Origin header.\"}";

  /** Longest accepted value: {@code https://} + a 253-character host + {@code :65535} is 270. */
  static final int MAX_ORIGIN_LENGTH = 300;

  private static final String NULL_ORIGIN = "null";

  /**
   * Every part is a distinct ASCII class and none can match the separator that follows it, so the
   * match is linear. Userinfo, path, query, fragment, whitespace and non-ASCII bytes cannot match.
   */
  private static final Pattern ORIGIN =
      Pattern.compile(
          "[A-Za-z][A-Za-z0-9+.\\-]*://(?:[A-Za-z0-9._~%\\-]+|\\[[0-9A-Fa-f:.]+])(?::[0-9]{1,5})?");

  /**
   * Tomcat answers some paths (for example under {@code /WEB-INF/}) with a 404 before the filter
   * chain and forwards the request to {@code /error}. The default of {@link OncePerRequestFilter}
   * skips that forward, so Spring would parse the header there; validate it too. A request that
   * already passed this filter is validated again, which costs one length check and a linear match.
   */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Enumeration<String> origins = request.getHeaders("Origin");
    while (origins != null && origins.hasMoreElements()) {
      String origin = origins.nextElement();
      if (!isValidOrigin(origin)) {
        warnF(
            "Rejecting {} {}: invalid Origin header of {} characters",
            request.getMethod(),
            request.getRequestURI(),
            origin == null ? 0 : origin.length());
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.getWriter().write(INVALID_ORIGIN);
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  static boolean isValidOrigin(String origin) {
    if (origin == null || origin.length() > MAX_ORIGIN_LENGTH) {
      return false;
    }
    return NULL_ORIGIN.equals(origin) || ORIGIN.matcher(origin).matches();
  }
}
