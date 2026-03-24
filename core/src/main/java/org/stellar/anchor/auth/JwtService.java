package org.stellar.anchor.auth;

import static java.util.Date.from;
import static org.stellar.anchor.util.JwtUtil.jwtsBuilder;
import static org.stellar.anchor.util.JwtUtil.jwtsParser;

import io.jsonwebtoken.*;
import java.lang.reflect.InvocationTargetException;
import java.security.KeyFactory;
import java.security.Security;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.NotSupportedException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.auth.ApiAuthJwt.CallbackAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.CustodyAuthJwt;
import org.stellar.anchor.auth.ApiAuthJwt.PlatformAuthJwt;
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep24MoreInfoUrlJwt;
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep6MoreInfoUrlJwt;
import org.stellar.anchor.config.CustodySecretConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.util.KeyUtil;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.KeyPair;

@Getter
@Builder
public class JwtService {
  // SEP-24 specific claims
  public static final String CLIENT_DOMAIN = "client_domain";
  public static final String HOME_DOMAIN = "home_domain";
  public static final String CLIENT_NAME = "client_name";

  String sep6MoreInfoUrlJwtSecret;
  String sep10JwtSecret;
  String sep45JwtSecret;
  String sep24InteractiveUrlJwtSecret;
  String sep24MoreInfoUrlJwtSecret;
  String callbackAuthSecret;
  String platformAuthSecret;
  String custodyAuthSecret;

  public JwtService(SecretConfig secretConfig, CustodySecretConfig custodySecretConfig)
      throws NotSupportedException {
    this(
        secretConfig.getSep6MoreInfoUrlJwtSecret(),
        secretConfig.getSep10JwtSecretKey(),
        secretConfig.getSep45JwtSecretKey(),
        secretConfig.getSep24InteractiveUrlJwtSecret(),
        secretConfig.getSep24MoreInfoUrlJwtSecret(),
        secretConfig.getCallbackAuthSecret(),
        secretConfig.getPlatformAuthSecret(),
        custodySecretConfig.getCustodyAuthSecret());
  }

  public JwtService(
      String sep6MoreInfoUrlJwtSecret,
      String sep10JwtSecret,
      String sep45JwtSecret,
      String sep24InteractiveUrlJwtSecret,
      String sep24MoreInfoUrlJwtSecret,
      String callbackAuthSecret,
      String platformAuthSecret,
      String custodyAuthSecret) {
    this.sep6MoreInfoUrlJwtSecret = sep6MoreInfoUrlJwtSecret;
    this.sep10JwtSecret = sep10JwtSecret;
    this.sep45JwtSecret = sep45JwtSecret;
    this.sep24InteractiveUrlJwtSecret = sep24InteractiveUrlJwtSecret;
    this.sep24MoreInfoUrlJwtSecret = sep24MoreInfoUrlJwtSecret;
    this.callbackAuthSecret = callbackAuthSecret;
    this.platformAuthSecret = platformAuthSecret;
    this.custodyAuthSecret = custodyAuthSecret;

    // Required for Ed25519 keys
    Security.addProvider(new BouncyCastleProvider());
  }

  public <T extends WebAuthJwt> String encode(T token) {
    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    Instant timeIat = Instant.ofEpochSecond(token.getIat());

    JwtBuilder builder =
        jwtsBuilder()
            .id(token.getJti())
            .issuer(token.getIss())
            .subject(token.getSub())
            .issuedAt(from(timeIat))
            .expiration(from(timeExp))
            .subject(token.getSub());

    if (token.getClientDomain() != null) {
      builder.claim(CLIENT_DOMAIN, token.getClientDomain());
    }

    if (token.getHomeDomain() != null) {
      builder.claim(HOME_DOMAIN, token.getHomeDomain());
    }

    if (token instanceof Sep45Jwt) {
      return signJWT(builder, sep45JwtSecret);
    } else {
      return signJWT(builder, sep10JwtSecret);
    }
  }

  public String encode(MoreInfoUrlJwt token) throws InvalidConfigException {
    var secret = getMoreInfoUrlSecret(token);

    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    JwtBuilder builder =
        jwtsBuilder().id(token.getJti()).expiration(from(timeExp)).subject(token.getSub());
    for (Map.Entry<String, Object> claim : token.claims.entrySet()) {
      builder.claim(claim.getKey(), claim.getValue());
    }

    return signJWT(builder, secret);
  }

  private String getMoreInfoUrlSecret(MoreInfoUrlJwt token) throws InvalidConfigException {
    if (token instanceof Sep6MoreInfoUrlJwt && sep6MoreInfoUrlJwtSecret != null) {
      return sep6MoreInfoUrlJwtSecret;
    } else if (token instanceof Sep24MoreInfoUrlJwt && sep24MoreInfoUrlJwtSecret != null) {
      return sep24MoreInfoUrlJwtSecret;
    } else {
      throw new InvalidConfigException(
          "Please provide the secret before encoding JWT for more_info_url");
    }
  }

  public String encode(Sep24InteractiveUrlJwt token) throws InvalidConfigException {
    if (sep24InteractiveUrlJwtSecret == null) {
      throw new InvalidConfigException(
          "Please provide the secret before encoding JWT for Sep24 interactive url");
    }
    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    JwtBuilder builder =
        jwtsBuilder().id(token.getJti()).expiration(from(timeExp)).subject(token.getSub());
    for (Map.Entry<String, Object> claim : token.claims.entrySet()) {
      builder.claim(claim.getKey(), claim.getValue());
    }

    return signJWT(builder, sep24InteractiveUrlJwtSecret);
  }

  private String signJWT(JwtBuilder builder, String secret) {
    return builder.signWith(KeyUtil.toSecretKeySpecOrNull(secret), Jwts.SIG.HS256).compact();
  }

  public String encode(CallbackAuthJwt token) throws InvalidConfigException {
    return encode(token, callbackAuthSecret);
  }

  public String encode(PlatformAuthJwt token) throws InvalidConfigException {
    return encode(token, platformAuthSecret);
  }

  public String encode(CustodyAuthJwt token) throws InvalidConfigException {
    return encode(token, custodyAuthSecret);
  }

  private String encode(ApiAuthJwt token, String secret) throws InvalidConfigException {
    if (secret == null) {
      throw new InvalidConfigException(
          "Please provide the secret before encoding JWT for API Authentication");
    }

    Instant timeExp = Instant.ofEpochSecond(token.getExp());
    Instant timeIat = Instant.ofEpochSecond(token.getIat());
    JwtBuilder builder = jwtsBuilder().issuedAt(from(timeIat)).expiration(from(timeExp));

    return builder.signWith(KeyUtil.toSecretKeySpecOrNull(secret), Jwts.SIG.HS256).compact();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public <T extends AbstractJwt> T decode(String cipher, Class<T> cls)
      throws NotSupportedException,
          NoSuchMethodException,
          InvocationTargetException,
          InstantiationException,
          IllegalAccessException {
    String secret;
    if (cls.equals(Sep6MoreInfoUrlJwt.class)) {
      secret = sep6MoreInfoUrlJwtSecret;
    } else if (cls.equals(Sep10Jwt.class)) {
      secret = sep10JwtSecret;
    } else if (cls.equals(Sep45Jwt.class)) {
      secret = sep45JwtSecret;
    } else if (cls.equals(Sep24InteractiveUrlJwt.class)) {
      secret = sep24InteractiveUrlJwtSecret;
    } else if (cls.equals(Sep24MoreInfoUrlJwt.class)) {
      secret = sep24MoreInfoUrlJwtSecret;
    } else if (cls.equals(CallbackAuthJwt.class)) {
      secret = callbackAuthSecret;
    } else if (cls.equals(PlatformAuthJwt.class)) {
      secret = platformAuthSecret;
    } else if (cls.equals(CustodyAuthJwt.class)) {
      secret = custodyAuthSecret;
    } else {
      throw new NotSupportedException(
          String.format("The Jwt class:[%s] is not supported", cls.getName()));
    }

    Jwt jwt = jwtsParser().verifyWith(KeyUtil.toSecretKeySpecOrNull(secret)).build().parse(cipher);

    if (cls.equals(Sep6MoreInfoUrlJwt.class)) {
      return (T) Sep6MoreInfoUrlJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(Sep10Jwt.class)) {
      return (T) Sep10Jwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(Sep45Jwt.class)) {
      return (T) Sep45Jwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(Sep24InteractiveUrlJwt.class)) {
      return (T) Sep24InteractiveUrlJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(Sep24MoreInfoUrlJwt.class)) {
      return (T) Sep24MoreInfoUrlJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(PlatformAuthJwt.class)) {
      return (T) PlatformAuthJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else if (cls.equals(CustodyAuthJwt.class)) {
      return (T) CustodyAuthJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    } else {
      return (T) CallbackAuthJwt.class.getConstructor(Jwt.class).newInstance(jwt);
    }
  }

  @SneakyThrows
  public Jws<Claims> getHeaderJwt(String signingKey, String cipher) {
    var factory = KeyFactory.getInstance("Ed25519");
    var pubKeyInfo =
        new SubjectPublicKeyInfo(
            new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519),
            KeyPair.fromAccountId(signingKey).getPublicKey());
    var x509KeySpec = new X509EncodedKeySpec(pubKeyInfo.getEncoded());
    var jcaPublicKey = factory.generatePublic(x509KeySpec);

    try {
      return jwtsParser().verifyWith(jcaPublicKey).build().parseSignedClaims(cipher);
    } catch (Exception e) {
      Log.debugF("Invalid header signature {}", e.getMessage());
      throw new SepValidationException("Invalid header signature");
    }
  }
}
