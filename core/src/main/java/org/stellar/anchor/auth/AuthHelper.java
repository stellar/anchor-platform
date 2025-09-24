package org.stellar.anchor.auth;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import java.util.Calendar;
import javax.annotation.Nullable;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.auth.ApiAuthJwt.CallbackAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.CustodyAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.PlatformAuthJwt;
import org.stellar.anchor.util.AuthHeader;

public class AuthHelper {
  public final AuthType authType;

  public final String authorizationHeader;
  private JwtService jwtService;
  private long jwtExpirationMilliseconds;
  private String apiKey;

  private AuthHelper(AuthType authType) {
    this(authType, "Authorization");
  }

  private AuthHelper(AuthType authType, String authorizationHeader) {
    this.authType = authType;
    this.authorizationHeader = authorizationHeader;
  }

  public static AuthHelper forJwtToken(JwtService jwtService, long jwtExpirationMilliseconds) {
    return forJwtToken("Authorization", jwtService, jwtExpirationMilliseconds);
  }

  public static AuthHelper forJwtToken(
      String authorizationHeader, JwtService jwtService, long jwtExpirationMilliseconds) {
    AuthHelper authHelper = new AuthHelper(AuthType.JWT, authorizationHeader);
    authHelper.jwtService = jwtService;
    authHelper.jwtExpirationMilliseconds = jwtExpirationMilliseconds;
    return authHelper;
  }

  public static AuthHelper forApiKey(String apiKey) {
    return forApiKey("X-Api-Key", apiKey);
  }

  public static AuthHelper forApiKey(String authorizationHeader, String apiKey) {
    AuthHelper authHelper = new AuthHelper(AuthType.API_KEY, authorizationHeader);
    authHelper.apiKey = apiKey;
    return authHelper;
  }

  public static AuthHelper forNone() {
    return new AuthHelper(AuthType.NONE);
  }

  @Nullable
  public AuthHeader<String, String> createPlatformServerAuthHeader() throws InvalidConfigException {
    return createAuthHeader(PlatformAuthJwt.class);
  }

  @Nullable
  public AuthHeader<String, String> createCallbackAuthHeader() throws InvalidConfigException {
    return createAuthHeader(CallbackAuthJwt.class);
  }

  @Nullable
  public AuthHeader<String, String> createCustodyAuthHeader() throws InvalidConfigException {
    return createAuthHeader(CustodyAuthJwt.class);
  }

  public static JwtBuilder jwtsBuilder() {
    return Jwts.builder().json(JwtsGsonSerializer.newInstance());
  }

  public static JwtParserBuilder jwtsParser() {
    return Jwts.parser().json(JwtsGsonDeserializer.newInstance());
  }

  @Nullable
  private <T extends ApiAuthJwt> AuthHeader<String, String> createAuthHeader(Class<T> jwtClass)
      throws InvalidConfigException {
    switch (authType) {
      case JWT:
        return new AuthHeader<>(authorizationHeader, "Bearer " + createJwt(jwtClass));
      case API_KEY:
        return new AuthHeader<>(authorizationHeader, apiKey);
      default:
        return null;
    }
  }

  private <T extends ApiAuthJwt> String createJwt(Class<T> jwtClass) throws InvalidConfigException {
    long issuedAt = Calendar.getInstance().getTimeInMillis() / 1000L;
    long expirationTime = issuedAt + (jwtExpirationMilliseconds / 1000L);

    if (jwtClass == CallbackAuthJwt.class) {
      CallbackAuthJwt token = new CallbackAuthJwt(issuedAt, expirationTime);
      return jwtService.encode(token);
    } else if (jwtClass == PlatformAuthJwt.class) {
      PlatformAuthJwt token = new PlatformAuthJwt(issuedAt, expirationTime);
      return jwtService.encode(token);
    } else if (jwtClass == CustodyAuthJwt.class) {
      CustodyAuthJwt token = new CustodyAuthJwt(issuedAt, expirationTime);
      return jwtService.encode(token);
    } else {
      throw new InvalidConfigException("Invalid JWT class: " + jwtClass);
    }
  }
}
