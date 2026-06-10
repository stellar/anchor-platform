package org.stellar.reference.client

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.util.Date
import org.stellar.anchor.util.GsonUtils

object JwtTokenProvider {
  fun createJwt(secret: String, expirationMilliseconds: Long): String {
    val issuedAt = Date()
    val expiresAt = Date(issuedAt.time + expirationMilliseconds)
    val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    return Jwts.builder()
      .json(GsonJwtSerializer())
      .issuedAt(issuedAt)
      .expiration(expiresAt)
      .signWith(key, Jwts.SIG.HS256)
      .compact()
  }
}

private class GsonJwtSerializer : io.jsonwebtoken.io.Serializer<Map<String, *>> {
  override fun serialize(t: Map<String, *>?): ByteArray {
    val json = GsonUtils.getInstance().toJson(t ?: emptyMap<String, Any?>())
    return json.toByteArray(StandardCharsets.UTF_8)
  }

  override fun serialize(t: Map<String, *>?, out: java.io.OutputStream) {
    val json = GsonUtils.getInstance().toJson(t ?: emptyMap<String, Any?>())
    out.write(json.toByteArray(StandardCharsets.UTF_8))
  }
}
