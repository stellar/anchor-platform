package org.stellar.anchor.sep10;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.crypto.SecretKey;

public final class KeyUtil {
  public static SecretKey toSecretKeySpecOrNull(String secret) {
    return Objects.toString(secret, "").isEmpty()
        ? null
        : Keys.hmacShaKeyFor(((secret.getBytes(StandardCharsets.UTF_8))));
  }
}
