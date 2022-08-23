package org.stellar.anchor.platform.utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Objects;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.stellar.anchor.util.Log;

/**
 * Log each request and response. The request body is never logged and the response body is only
 * logged when it's an error
 *
 * @see <a href="https://stackoverflow.com/a/42023374/875657">StackOverflow Answer</a>
 */
public class RequestLoggerFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    long startTime = System.currentTimeMillis();

    // ========= Log request and response payload ("body") ========
    // We CANNOT simply read the request payload here, because then the InputStream would be
    // consumed and cannot be read again by the actual processing/server.
    // So we need to apply some stronger magic here :-)
    ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

    // IMPORTANT: This performs the actual request!
    filterChain.doFilter(wrappedRequest, wrappedResponse);
    long duration = System.currentTimeMillis() - startTime;

    String principalName =
        request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
    RequestResponseMessage requestResponseMessage =
        RequestResponseMessage.builder()
            .request(
                RequestResponseMessage.Request.builder()
                    .method(request.getMethod())
                    .path(request.getRequestURI())
                    .queryParams(request.getQueryString())
                    .authType(request.getAuthType())
                    .principalName(principalName)
                    .clientId(getClientIpAddress(request))
                    .build())
            .response(
                RequestResponseMessage.Response.builder()
                    .statusCode(response.getStatus())
                    .responseBody(getBody(wrappedResponse))
                    .build())
            .durationMilliseconds(duration)
            .build();

    Log.info(requestResponseMessage.toString());

    // IMPORTANT: copy content of response back into original response:
    wrappedResponse.copyBodyToResponse();
  }

  /**
   * getBody will get the response body (if it's an error) or omit it if it's not an error.
   *
   * @param wrappedResponse the wrapped response
   * @return the response body, if it's an error, or "[hidden]" if it's not.
   */
  private String getBody(ContentCachingResponseWrapper wrappedResponse) {
    byte[] buf = wrappedResponse.getContentAsByteArray();

    if (Objects.toString(buf, "").isEmpty()) {
      return "";
    }

    int length = Math.min(buf.length, 2048);
    try {
      String body = new String(buf, 0, length, wrappedResponse.getCharacterEncoding());
      return body.contains("\"error\"") ? body : "[hidden]";
    } catch (UnsupportedEncodingException ex) {
      return "Unsupported Encoding";
    }
  }

  public static String getClientIpAddress(HttpServletRequest request) {
    final String[] IP_HEADER_CANDIDATES = {
      "X-Forwarded-For",
      "X-Real-IP",
      "Proxy-Client-IP",
      "WL-Proxy-Client-IP",
      "HTTP_X_FORWARDED_FOR",
      "HTTP_X_FORWARDED",
      "HTTP_X_CLUSTER_CLIENT_IP",
      "HTTP_CLIENT_IP",
      "HTTP_FORWARDED_FOR",
      "HTTP_FORWARDED",
      "HTTP_VIA",
      "REMOTE_ADDR"
    };

    String ip = request.getRemoteAddr();

    for (String headerName : IP_HEADER_CANDIDATES) {
      String headerValue = request.getHeader(headerName);
      if (headerValue != null
          && headerValue.length() != 0
          && !"unknown".equalsIgnoreCase(headerValue)) {
        ip = headerValue.split(",")[0];
        break;
      }
    }

    if (ip.equals("0:0:0:0:0:0:0:1")) {
      ip = "127.0.0.1";
    }
    return ip;
  }
}
