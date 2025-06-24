package org.stellar.anchor.auth;

import io.jsonwebtoken.Jwt;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Sep10Jwt extends WebAuthJwt {

  public Sep10Jwt(Jwt jwt) {
    super(jwt);
  }

  public Sep10Jwt(
      String iss,
      String sub,
      long iat,
      long exp,
      String jti,
      String clientDomain,
      String homeDomain) {
    super(iss, sub, iat, exp, jti, clientDomain, homeDomain);
  }

  public static Sep10Jwt of(
      String iss,
      String sub,
      long iat,
      long exp,
      String jti,
      String clientDomain,
      String homeDomain) {
    return new Sep10Jwt(iss, sub, iat, exp, jti, clientDomain, homeDomain);
  }
}
