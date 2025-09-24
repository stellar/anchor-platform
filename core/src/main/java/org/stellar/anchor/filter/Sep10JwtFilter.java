package org.stellar.anchor.filter;

import static org.stellar.anchor.util.Log.*;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import lombok.NonNull;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.Sep10Jwt;

public class Sep10JwtFilter extends AbstractJwtFilter {
  public Sep10JwtFilter(JwtService jwtService) {
    // SEP-10 tokens are passed in the Authorization header.
    super(jwtService, "Authorization");
  }

  @Override
  public void check(String jwtCipher, HttpServletRequest request, ServletResponse servletResponse)
      throws Exception {
    @NonNull Sep10Jwt token = jwtService.decode(jwtCipher, Sep10Jwt.class);
    infoF("token created. account={} url={}", shorter(token.getAccount()), request.getRequestURL());
    debugF("storing token to request {}:", request.getRequestURL(), token);
    request.setAttribute(JWT_TOKEN, token);
  }
}
