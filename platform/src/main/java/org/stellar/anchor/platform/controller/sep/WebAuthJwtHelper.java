package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.filter.WebAuthJwtFilter.JWT_TOKEN;

import jakarta.servlet.http.HttpServletRequest;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.auth.WebAuthJwt;

public class WebAuthJwtHelper {
  public static WebAuthJwt getToken(HttpServletRequest request) throws SepValidationException {
    WebAuthJwt token = (WebAuthJwt) request.getAttribute(JWT_TOKEN);
    if (token == null) {
      throw new SepValidationException(
          "missing web auth jwt token. Make sure the web auth jwt filter is invoked before the controller");
    }
    return token;
  }
}
