package org.stellar.reference.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import org.stellar.reference.data.JwtToken

object JwtDecoder {
  fun decode(cipher: String, jwtKey: String, audience: String? = null): JwtToken {
    val secretKeySpec = Keys.hmacShaKeyFor(jwtKey.toByteArray(StandardCharsets.UTF_8))
    val jwt = Jwts.parser().verifyWith(secretKeySpec).build().parseSignedClaims(cipher)

    val claims: Claims = jwt.payload

    if (audience != null) {
      val tokenAudience = claims.audience
      if (!tokenAudience.isNullOrEmpty() && !tokenAudience.contains(audience)) {
        throw IllegalArgumentException("Token audience does not match")
      }
    }

    @Suppress("UNCHECKED_CAST")
    return JwtToken(
      claims["jti"] as String,
      claims["exp"].toString().toLong(),
      (claims["data"] as? Map<String, String>) ?: emptyMap(),
    )
  }
}
