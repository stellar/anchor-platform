package org.stellar.anchor.filter;

import static org.stellar.anchor.util.Log.*;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.WebAuthJwt;

public class Sep10JwtFilter extends AbstractJwtFilter {
  public Sep10JwtFilter(JwtService jwtService) {
    // SEP-10 tokens are passed in the Authorization header.
    super(jwtService, "Authorization");
  }

  @Override
  public void check(String jwtCipher, HttpServletRequest request, ServletResponse servletResponse)
      throws Exception {
    @NonNull WebAuthJwt token = jwtService.decode(jwtCipher, WebAuthJwt.class);
    infoF("token created. account={} url={}", shorter(token.getAccount()), request.getRequestURL());
    debugF("storing token to request {}:", request.getRequestURL(), token);
    request.setAttribute(JWT_TOKEN, token);
  }
}
