package org.stellar.reference.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

object Sep24SessionToken {
  const val AUDIENCE = "sep24_reference_session"
  const val DEFAULT_TTL_SECONDS = 1800L

  fun issue(
    transactionId: String,
    jwtKey: String,
    ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    now: Long = System.currentTimeMillis(),
  ): String =
    Jwts.builder()
      .id(transactionId)
      .audience()
      .add(AUDIENCE)
      .and()
      .issuedAt(Date(now))
      .expiration(Date(now + ttlSeconds * 1000))
      .signWith(key(jwtKey))
      .compact()

  fun verify(token: String, jwtKey: String): String {
    val claims =
      Jwts.parser()
        .verifyWith(key(jwtKey))
        .requireAudience(AUDIENCE)
        .build()
        .parseSignedClaims(token)
        .payload
    return claims.id ?: throw IllegalArgumentException("Session token has no transaction id")
  }

  private fun key(jwtKey: String): SecretKey =
    Keys.hmacShaKeyFor(jwtKey.toByteArray(StandardCharsets.UTF_8))
}
