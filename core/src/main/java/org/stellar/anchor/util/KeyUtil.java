package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.validation.Errors;

public final class KeyUtil {
  public static SecretKey toSecretKeySpecOrNull(String secret) {
    return isEmpty(secret) ? null : Keys.hmacShaKeyFor(((secret.getBytes(StandardCharsets.UTF_8))));
  }

  public static void validateJWTSecret(String secret) {
    toSecretKeySpecOrNull(secret);
  }

  public static void rejectWeakJWTSecret(String secret, Errors errors, String name) {
    try {
      KeyUtil.validateJWTSecret(secret);
    } catch (WeakKeyException e) {
      errors.reject("hmac-weak-secret", name + " is too weak: " + e.getMessage());
    }
  }
}
