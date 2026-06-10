package org.stellar.reference.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import org.stellar.reference.data.AuthSettings

object AuthHeaderUtil {
  fun addAuthHeaderIfNeeded(builder: HttpRequestBuilder, authSettings: AuthSettings) {
    if (authSettings.type != AuthSettings.Type.JWT) {
      return
    }
    val token =
      JwtTokenProvider.createJwt(
        authSettings.anchorToPlatformSecret,
        authSettings.expirationMilliseconds,
      )
    builder.headers.append(HttpHeaders.Authorization, "Bearer $token")
  }
}
