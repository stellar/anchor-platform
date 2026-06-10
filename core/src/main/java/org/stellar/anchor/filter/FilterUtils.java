package org.stellar.anchor.filter;

import jakarta.servlet.http.HttpServletRequest;

final class FilterUtils {
  static String getRequestPath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath != null && !servletPath.isEmpty()) {
      return servletPath;
    }

    String pathInfo = request.getPathInfo();
    if (pathInfo != null && !pathInfo.isEmpty()) {
      return pathInfo;
    }

    String requestUri = request.getRequestURI();
    if (requestUri == null) {
      return "";
    }

    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
      return requestUri.substring(contextPath.length());
    }

    return requestUri;
  }

  private FilterUtils() {}
}
