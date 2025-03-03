package org.stellar.anchor.filter;

import static org.stellar.anchor.util.Log.*;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.auth.Sep45Jwt;
import org.stellar.anchor.auth.WebAuthJwt;

public class WebAuthJwtFilter extends AbstractJwtFilter {
  public WebAuthJwtFilter(JwtService jwtService) {
    // SEP-10/SEP-45 tokens are passed in the Authorization header.
    super(jwtService, "Authorization");
  }

  @Override
  public void check(String jwtCipher, HttpServletRequest request, ServletResponse servletResponse)
      throws Exception {
    WebAuthJwt token = null;
    try {
      token = jwtService.decode(jwtCipher, Sep10Jwt.class);
    } catch (Exception ignored) {
      token = jwtService.decode(jwtCipher, Sep45Jwt.class);
    } finally {
      infoF(
          "token created. account={} url={}", shorter(token.getAccount()), request.getRequestURL());
      debugF("storing token to request {}:", request.getRequestURL(), token);
      request.setAttribute(JWT_TOKEN, token);
    }
  }
}
