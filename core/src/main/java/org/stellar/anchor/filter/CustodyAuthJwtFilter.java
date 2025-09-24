package org.stellar.anchor.filter;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import lombok.NonNull;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.auth.ApiAuthJwt.CustodyAuthJwt;
import org.stellar.anchor.auth.JwtService;

public class CustodyAuthJwtFilter extends AbstractJwtFilter {

  public CustodyAuthJwtFilter(JwtService jwtService, String authorizationHeader) {
    super(jwtService, authorizationHeader);
  }

  @Override
  public void check(String jwtCipher, HttpServletRequest request, ServletResponse servletResponse)
      throws Exception {
    @NonNull CustodyAuthJwt token = jwtService.decode(jwtCipher, CustodyAuthJwt.class);
    if (token == null) {
      throw new SepValidationException("JwtToken should not be null");
    }

    request.setAttribute(JWT_TOKEN, token);
  }
}
