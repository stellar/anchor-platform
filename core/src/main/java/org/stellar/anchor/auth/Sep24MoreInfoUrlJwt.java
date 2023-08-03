package org.stellar.anchor.auth;

import static org.stellar.anchor.auth.JwtService.CLIENT_DOMAIN;

import io.jsonwebtoken.Jwt;
import org.stellar.anchor.api.exception.SepException;

public class Sep24MoreInfoUrlJwt extends AbstractJwt {
  public Sep24MoreInfoUrlJwt(String sub, String jti, long exp, String clientDomain)
      throws SepException {
    super.sub = sub;
    super.jti = jti;
    super.exp = exp;
    super.claim(CLIENT_DOMAIN, clientDomain);
  }

  public Sep24MoreInfoUrlJwt(Jwt jwt) {
    super(jwt);
  }
}
