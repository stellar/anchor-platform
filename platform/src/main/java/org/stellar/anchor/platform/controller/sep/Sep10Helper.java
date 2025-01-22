package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.filter.Sep10JwtFilter.JWT_TOKEN;

import jakarta.servlet.http.HttpServletRequest;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.auth.WebAuthJwt;

public class Sep10Helper {
  public static WebAuthJwt getSep10Token(HttpServletRequest request) throws SepValidationException {
    WebAuthJwt token = (WebAuthJwt) request.getAttribute(JWT_TOKEN);
    if (token == null) {
      throw new SepValidationException(
          "missing sep10 jwt token. Make sure the sep10 filter is invoked before the controller");
    }
    return token;
  }
}
