package org.stellar.anchor.auth;

import java.util.Calendar;
import javax.annotation.Nullable;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.util.AuthHeader;

public class AuthHelper {
  public final AuthType authType;
  private JwtService jwtService;
  private long jwtExpirationMilliseconds;
  private String apiKey;

  private AuthHelper(AuthType authType) {
    this.authType = authType;
  }

  public static AuthHelper from(AuthType type, String secret, long jwtExpirationMilliseconds) {
    switch (type) {
      case JWT:
        return AuthHelper.forJwtToken(
            new JwtService(null, null, null, secret, secret), jwtExpirationMilliseconds);
      case API_KEY:
        return AuthHelper.forApiKey(secret);
      default:
        return AuthHelper.forNone();
    }
  }

  public static AuthHelper forJwtToken(JwtService jwtService, long jwtExpirationMilliseconds) {
    AuthHelper authHelper = new AuthHelper(AuthType.JWT);
    authHelper.jwtService = jwtService;
    authHelper.jwtExpirationMilliseconds = jwtExpirationMilliseconds;
    return authHelper;
  }

  public static AuthHelper forApiKey(String apiKey) {
    AuthHelper authHelper = new AuthHelper(AuthType.API_KEY);
    authHelper.apiKey = apiKey;
    return authHelper;
  }

  public static AuthHelper forNone() {
    return new AuthHelper(AuthType.NONE);
  }

  @Nullable
  public AuthHeader<String, String> createAuthHeader() throws InvalidConfigException {
    switch (authType) {
      case JWT:
        long issuedAt = Calendar.getInstance().getTimeInMillis() / 1000L;
        long expirationTime = issuedAt + (jwtExpirationMilliseconds / 1000L);
        ApiAuthJwt token = new ApiAuthJwt(issuedAt, expirationTime);
        return new AuthHeader<>("Authorization", "Bearer " + jwtService.encode(token));
      case API_KEY:
        return new AuthHeader<>("X-Api-Key", apiKey);
      default:
        return null;
    }
  }
}
