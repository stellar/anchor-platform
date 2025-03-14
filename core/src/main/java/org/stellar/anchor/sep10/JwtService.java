package org.stellar.anchor.sep10;

import static org.stellar.anchor.sep10.JwtToken.REQUESTED_ACCOUNT;

import io.jsonwebtoken.*;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Map;
import org.apache.commons.codec.binary.Base64;
import org.stellar.anchor.config.AppConfig;

public class JwtService {
  final String jwtKey;

  public JwtService(AppConfig appConfig) {
    this(appConfig.getJwtSecretKey());
  }

  public JwtService(String secretKey) {
    this.jwtKey = Base64.encodeBase64String(secretKey.getBytes(StandardCharsets.UTF_8));
  }

  public String encode(JwtToken token) {
    Calendar calIat = Calendar.getInstance();
    calIat.setTimeInMillis(1000L * token.getIat());

    Calendar calExp = Calendar.getInstance();
    calExp.setTimeInMillis(1000L * token.getExp());

    JwtBuilder builder =
        Jwts.builder()
            .setId(token.getJti())
            .setIssuer(token.getIss())
            .setSubject(token.getSub())
            .setIssuedAt(calIat.getTime())
            .setExpiration(calExp.getTime())
            .setSubject(token.sub);

    if (token.clientDomain != null) {
      builder.addClaims(Map.of("client_domain", token.clientDomain));
    }

    if (token.requestedAccount != null) {
      builder.addClaims(Map.of(REQUESTED_ACCOUNT, token.requestedAccount));
    }

    return builder.signWith(KeyUtil.toSecretKeySpecOrNull(jwtKey), Jwts.SIG.HS256).compact();
  }

  @SuppressWarnings("rawtypes")
  public JwtToken decode(String cipher) {

    JwtParser jwtParser =
        Jwts.parser()
            .json(JwtsGsonDeserializer.newInstance())
            .verifyWith(KeyUtil.toSecretKeySpecOrNull(jwtKey))
            .build();
    Jwt jwt = jwtParser.parse(cipher);
    Claims claims = (Claims) jwt.getBody();
    Object requestedAccount = claims.get(REQUESTED_ACCOUNT);
    return JwtToken.of(
        (String) claims.get("iss"),
        (String) claims.get("sub"),
        (Long) claims.get("iat"),
        (Long) claims.get("exp"),
        (String) claims.get("jti"),
        (String) claims.get("client_domain"),
        requestedAccount == null ? null : (String) requestedAccount);
  }
}
