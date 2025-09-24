package org.stellar.anchor.filter;

import static org.stellar.anchor.util.Log.*;

import com.google.gson.Gson;
import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import lombok.SneakyThrows;
import org.apache.http.HttpStatus;
import org.stellar.anchor.api.sep.SepExceptionResponse;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;

public class ApiKeyFilter implements Filter {
  private static final String OPTIONS = "OPTIONS";
  private static final String APPLICATION_JSON_VALUE = "application/json";
  private static final Gson gson = GsonUtils.builder().setPrettyPrinting().create();
  private final String apiKey;
  private final String authorizationHeader;

  public ApiKeyFilter(@NotNull String apiKey, String authorizationHeader) {
    this.apiKey = apiKey;
    this.authorizationHeader = authorizationHeader;
  }

  @Override
  public void init(FilterConfig filterConfig) {}

  @SneakyThrows
  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) {
    if (!(servletRequest instanceof HttpServletRequest)) {
      throw new ServletException("the request must be a HttpServletRequest");
    }

    if (!(servletResponse instanceof HttpServletResponse)) {
      throw new ServletException("the request must be a HttpServletRequest");
    }

    HttpServletRequest request = (HttpServletRequest) servletRequest;
    HttpServletResponse response = (HttpServletResponse) servletResponse;
    Log.infoF(
        "Applying ApiKeyFilter on request {} {}?{}",
        request.getMethod(),
        request.getRequestURL().toString(),
        request.getQueryString());

    if (request.getMethod().equals(OPTIONS)) {
      filterChain.doFilter(servletRequest, servletResponse);
      return;
    }

    String gotApiKey = request.getHeader(authorizationHeader);
    if (!apiKey.equals(gotApiKey)) {
      sendForbiddenError(response);
      return;
    }

    infoF("apiKey auth passed for url={}", request.getRequestURL());

    filterChain.doFilter(servletRequest, servletResponse);
  }

  private static void sendForbiddenError(HttpServletResponse response) throws IOException {
    error("Forbidden: ApiKeyFilter failed to authenticate the request.");
    response.setStatus(HttpStatus.SC_FORBIDDEN);
    response.setContentType(APPLICATION_JSON_VALUE);
    response.getWriter().print(gson.toJson(new SepExceptionResponse("forbidden")));
  }

  @Override
  public void destroy() {}
}
