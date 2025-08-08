package org.stellar.anchor.auth

import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.*
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Order
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.stellar.anchor.auth.ApiAuthJwt.*
import org.stellar.anchor.auth.AuthType.*
import org.stellar.anchor.lockAndMockStatic
import org.stellar.anchor.util.AuthHeader

@Order(86)
class AuthHelperTest {
  companion object {
    const val JWT_EXPIRATION_MILLISECONDS: Long = 90000

    @JvmStatic
    fun authHelperTests(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(JWT, "Authorization"),
        Arguments.of(JWT, "Custom_Authorization"),
        Arguments.of(API_KEY, "Authorization"),
        Arguments.of(API_KEY, "Custom_Authorization"),
        Arguments.of(NONE, null),
      )
    }
  }

  @ParameterizedTest
  @MethodSource("authHelperTests")
  fun `test AuthHeader creation with different AuthType and authorization headers`(
    authType: AuthType,
    headerName: String?,
  ) {
    lockAndMockStatic(Calendar::class) {
      val calendarSingleton = mockk<Calendar>(relaxed = true)
      // Mock calendar to guarantee the jwt token format
      when (authType) {
        JWT -> {
          val currentTimeMilliseconds = Instant.now().toEpochMilli()
          every { calendarSingleton.timeInMillis } returns currentTimeMilliseconds
          every { calendarSingleton.time } returns Date(currentTimeMilliseconds)
          every { calendarSingleton.timeInMillis = any() } answers {}
          every { Calendar.getInstance() } returns calendarSingleton

          // mock jwt token based on the mocked calendar
          val wantPlatformJwt =
            PlatformAuthJwt(
              currentTimeMilliseconds / 1000L,
              (currentTimeMilliseconds + JWT_EXPIRATION_MILLISECONDS) / 1000L,
            )
          val wantCallbackJwt =
            CallbackAuthJwt(
              currentTimeMilliseconds / 1000L,
              (currentTimeMilliseconds + JWT_EXPIRATION_MILLISECONDS) / 1000L,
            )

          var jwtService =
            JwtService.builder()
              .platformAuthSecret("secret__________________________________")
              .build()
          var authHelper =
            AuthHelper.forJwtToken(
              headerName,
              jwtService,
              JWT_EXPIRATION_MILLISECONDS,
              PlatformAuthJwt::class.java
            )

          val gotPlatformAuthHeader = authHelper.createAuthHeader()
          val wantPlatformAuthHeader =
            AuthHeader(headerName, "Bearer ${jwtService.encode(wantPlatformJwt)}")
          assertEquals(wantPlatformAuthHeader, gotPlatformAuthHeader)

          jwtService =
            JwtService.builder()
              .callbackAuthSecret("secret__________________________________")
              .build()
          authHelper =
            AuthHelper.forJwtToken(
              headerName,
              jwtService,
              JWT_EXPIRATION_MILLISECONDS,
              CallbackAuthJwt::class.java
            )
          val gotCallbackAuthHeader = authHelper.createAuthHeader()
          val wantCallbackAuthHeader =
            AuthHeader(headerName, "Bearer ${jwtService.encode(wantCallbackJwt)}")
          assertEquals(wantCallbackAuthHeader, gotCallbackAuthHeader)
        }
        API_KEY -> {
          val authHelper = AuthHelper.forApiKey("X-Api-Key", "secret")
          val gotPlatformAuthHeader = authHelper.createAuthHeader()
          val wantPlatformAuthHeader = AuthHeader("X-Api-Key", "secret")
          assertEquals(wantPlatformAuthHeader, gotPlatformAuthHeader)
        }
        NONE -> {
          val authHelper = AuthHelper.forNone()
          val platformAuthHeader = authHelper.createAuthHeader()
          assertNull(platformAuthHeader)
        }
        else -> {
          throw Exception("Unsupported new AuthType!!!")
        }
      }
    }
  }
}
