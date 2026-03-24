package org.stellar.anchor.auth;

import java.util.Calendar;
import lombok.SneakyThrows;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.auth.ApiAuthJwt.CallbackAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.PlatformAuthJwt;
import org.stellar.anchor.util.AuthHeader;

public interface AuthHelper {
  static <T> AuthHelper forJwtToken(
      JwtService jwtService, long jwtExpirationMilliseconds, Class<T> jwtClass) {
    return forJwtToken("Authorization", jwtService, jwtExpirationMilliseconds, jwtClass);
  }

  static <T> AuthHelper forJwtToken(
      String authorizationHeader,
      JwtService jwtService,
      long jwtExpirationMilliseconds,
      Class<T> jwtClass) {
    return new JwtAuthHelper<>(
        authorizationHeader, jwtService, jwtExpirationMilliseconds, jwtClass);
  }

  static AuthHelper forApiKey(String apiKey) {
    return new ApiAuthHelper(apiKey);
  }

  static AuthHelper forApiKey(String authorizationHeader, String apiKey) {
    return new ApiAuthHelper(apiKey);
  }

  static AuthHelper forNone() {
    return new NoneAuthHelper();
  }

  AuthType getAuthType();

  AuthHeader<String, String> createAuthHeader();

  class NoneAuthHelper implements AuthHelper {
    @Override
    public AuthType getAuthType() {
      return AuthType.NONE;
    }

    @Override
    public AuthHeader<String, String> createAuthHeader() {
      return null;
    }
  }

  class ApiAuthHelper implements AuthHelper {
    private final String apiKey;
    private final String authorizationHeader;

    @Override
    public AuthType getAuthType() {
      return AuthType.API_KEY;
    }

    public ApiAuthHelper(String apiKey, String authorizationHeader) {
      this.apiKey = apiKey;
      this.authorizationHeader = authorizationHeader;
    }

    public ApiAuthHelper(String apiKey) {
      this(apiKey, "X-Api-Key");
    }

    @Override
    public AuthHeader<String, String> createAuthHeader() {
      return new AuthHeader<>(authorizationHeader, apiKey);
    }
  }

  class JwtAuthHelper<T> implements AuthHelper {
    private final String authorizationHeader;
    private final JwtService jwtService;
    private final long jwtExpirationMilliseconds;
    private final Class<T> jwtClass;

    @Override
    public AuthType getAuthType() {
      return AuthType.JWT;
    }

    public JwtAuthHelper(
        String authorizationHeader,
        JwtService jwtService,
        long jwtExpirationMilliseconds,
        Class<T> jwtClass) {
      this.authorizationHeader = authorizationHeader;
      this.jwtService = jwtService;
      this.jwtExpirationMilliseconds = jwtExpirationMilliseconds;
      this.jwtClass = jwtClass;
    }

    @Override
    public AuthHeader<String, String> createAuthHeader() {
      return new AuthHeader<>(authorizationHeader, "Bearer " + createJwt());
    }

    @SneakyThrows
    private String createJwt() {
      long issuedAt = Calendar.getInstance().getTimeInMillis() / 1000L;
      long expirationTime = issuedAt + (jwtExpirationMilliseconds / 1000L);

      if (jwtClass == CallbackAuthJwt.class) {
        CallbackAuthJwt token = new CallbackAuthJwt(issuedAt, expirationTime);
        return jwtService.encode(token);
      } else if (jwtClass == PlatformAuthJwt.class) {
        PlatformAuthJwt token = new PlatformAuthJwt(issuedAt, expirationTime);
        return jwtService.encode(token);
      } else {
        throw new InvalidConfigException("Invalid JWT class: " + jwtClass);
      }
    }
  }
}
