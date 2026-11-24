package org.stellar.anchor.util;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.CompressionAlgorithm;
import java.io.InputStream;
import java.io.OutputStream;
import org.stellar.anchor.auth.JwtsGsonDeserializer;
import org.stellar.anchor.auth.JwtsGsonSerializer;

public final class JwtUtil {
  public static JwtBuilder jwtsBuilder() {
    return Jwts.builder().json(JwtsGsonSerializer.newInstance());
  }

  /**
   * Builds a parser that refuses compressed JWTs. jjwt inflates a {@code zip} payload with no size
   * cap, so a few KB of token can expand to many MB. The platform never issues compressed tokens,
   * and {@code zip} is not a JWS header (RFC 7516 defines it for JWE only), so the two algorithms
   * jjwt ships are replaced with ones that refuse to decompress. Other {@code zip} values are
   * already rejected by jjwt.
   */
  public static JwtParserBuilder jwtsParser() {
    return Jwts.parser()
        .json(JwtsGsonDeserializer.newInstance())
        .zip()
        .add(new RefusedCompression(Jwts.ZIP.DEF.getId()))
        .add(new RefusedCompression(Jwts.ZIP.GZIP.getId()))
        .and();
  }

  static final class RefusedCompression implements CompressionAlgorithm {
    private static final String MESSAGE = "Compressed JWTs are not supported";
    private final String id;

    RefusedCompression(String id) {
      this.id = id;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public OutputStream compress(OutputStream out) {
      throw new UnsupportedJwtException(MESSAGE);
    }

    @Override
    public InputStream decompress(InputStream in) {
      throw new UnsupportedJwtException(MESSAGE);
    }
  }
}
