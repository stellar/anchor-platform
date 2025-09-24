package org.stellar.anchor.platform.callback

import io.mockk.*
import java.util.*
import kotlin.test.assertEquals
import okhttp3.Request
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.LockStatic
import org.stellar.anchor.auth.*
import org.stellar.anchor.auth.ApiAuthJwt.PlatformAuthJwt
import org.stellar.anchor.auth.AuthType.*
import org.stellar.anchor.auth.JwtService

@ExtendWith(LockAndMockTest::class)
class PlatformIntegrationHelperTest {
  companion object {
    const val JWT_EXPIRATION_MILLISECONDS: Long = 90000
    const val TEST_HOME_DOMAIN = "https://test.stellar.org"
  }

  @ParameterizedTest
  @EnumSource(AuthType::class)
  @LockStatic([Calendar::class])
  fun test_getRequestBuilder(authType: AuthType) {
    when (authType) {
      JWT -> {
        val calendarSingleton = mockk<Calendar>()
        every { calendarSingleton.timeInMillis } returns System.currentTimeMillis()
        val currentTimeMilliseconds = calendarSingleton.timeInMillis
        every { calendarSingleton.timeInMillis } returns currentTimeMilliseconds
        every { calendarSingleton.timeInMillis = any() } answers { callOriginal() }
        // Mock calendar to guarantee the jwt token format
        mockkStatic(Calendar::class)
        every { Calendar.getInstance() } returns calendarSingleton

        // mock jwt token based on the mocked calendar
        val wantJwtToken =
          PlatformAuthJwt(
            currentTimeMilliseconds / 1000L,
            (currentTimeMilliseconds + JWT_EXPIRATION_MILLISECONDS) / 1000L,
          )

        val jwtService =
          JwtService.builder()
            .callbackAuthSecret("secret__________________________________")
            .platformAuthSecret("secret__________________________________")
            .custodyAuthSecret("secret__________________________________")
            .build()
        val authHelper = AuthHelper.forJwtToken(jwtService, JWT_EXPIRATION_MILLISECONDS)

        val gotRequestBuilder = PlatformIntegrationHelper.getRequestBuilder(authHelper)
        val gotRequest = gotRequestBuilder.url(TEST_HOME_DOMAIN).get().build()
        val wantRequestBuilder: Request.Builder =
          Request.Builder()
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${jwtService.encode(wantJwtToken)}")
        val wantRequest = wantRequestBuilder.url(TEST_HOME_DOMAIN).get().build()
        assertEquals(wantRequest.headers, gotRequest.headers)
      }
      API_KEY -> {
        val authHelper = AuthHelper.forApiKey("secret")
        val gotRequestBuilder = PlatformIntegrationHelper.getRequestBuilder(authHelper)
        val gotRequest = gotRequestBuilder.url(TEST_HOME_DOMAIN).get().build()
        val wantRequestBuilder: Request.Builder =
          Request.Builder().header("Content-Type", "application/json").header("X-Api-Key", "secret")
        val wantRequest = wantRequestBuilder.url(TEST_HOME_DOMAIN).get().build()
        assertEquals(wantRequest.headers, gotRequest.headers)
      }
      NONE -> {
        val authHelper = AuthHelper.forNone()
        val gotRequestBuilder = PlatformIntegrationHelper.getRequestBuilder(authHelper)
        val gotRequest = gotRequestBuilder.url(TEST_HOME_DOMAIN).get().build()
        val wantRequestBuilder: Request.Builder =
          Request.Builder().header("Content-Type", "application/json")
        val wantRequest = wantRequestBuilder.url(TEST_HOME_DOMAIN).get().build()
        assertEquals(wantRequest.headers, gotRequest.headers)
      }
      else -> {
        throw Exception("Unsupported new AuthType!!!")
      }
    }
  }
}
