package org.stellar.anchor.auth;

import io.jsonwebtoken.Jwt;

public class ApiAuthJwt extends AbstractJwt {
  ApiAuthJwt(Jwt jwt) {
    super(jwt);
  }

  ApiAuthJwt(long iat, long exp) {
    super.iat = iat;
    super.exp = exp;
  }

  public static class PlatformAuthJwt extends ApiAuthJwt {
    public PlatformAuthJwt(long iat, long exp) {
      super(iat, exp);
    }

    public PlatformAuthJwt(Jwt jwt) {
      super(jwt);
    }
  }

  public static class CallbackAuthJwt extends ApiAuthJwt {
    public CallbackAuthJwt(long iat, long exp) {
      super(iat, exp);
    }

    public CallbackAuthJwt(Jwt jwt) {
      super(jwt);
    }
  }
}
