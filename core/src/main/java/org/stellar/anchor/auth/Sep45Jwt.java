package org.stellar.anchor.auth;

import io.jsonwebtoken.Jwt;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Sep45Jwt extends WebAuthJwt {

  public Sep45Jwt(Jwt jwt) {
    super(jwt);
  }

  public Sep45Jwt(
      String iss,
      String sub,
      long iat,
      long exp,
      String jti,
      String clientDomain,
      String homeDomain) {
    super(iss, sub, iat, exp, jti, clientDomain, homeDomain);
  }

  public static Sep45Jwt of(
      String iss,
      String sub,
      long iat,
      long exp,
      String jti,
      String clientDomain,
      String homeDomain) {
    return new Sep45Jwt(iss, sub, iat, exp, jti, clientDomain, homeDomain);
  }

  @Override
  public String getAccountMemo() {
    throw new UnsupportedOperationException("SEP-45 does not support account memos");
  }

  @Override
  public String getMuxedAccount() {
    throw new UnsupportedOperationException("SEP-45 does not support muxed accounts");
  }

  @Override
  public Long getMuxedAccountId() {
    throw new UnsupportedOperationException("SEP-45 does not support muxed accounts");
  }
}
