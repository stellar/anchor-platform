package org.stellar.anchor.filter;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.stellar.anchor.auth.ApiAuthJwt;
import org.stellar.anchor.auth.JwtService;

public class CallbackAuthJwtFilter extends AbstractJwtFilter {
  public CallbackAuthJwtFilter(JwtService jwtService) {
    super(jwtService);
  }

  @Override
  void check(String jwtCipher, HttpServletRequest request, ServletResponse servletResponse)
      throws Exception {
    ApiAuthJwt.CallbackAuthJwt token =
        jwtService.decode(jwtCipher, ApiAuthJwt.CallbackAuthJwt.class);
    request.setAttribute(JWT_TOKEN, token);
  }
}
