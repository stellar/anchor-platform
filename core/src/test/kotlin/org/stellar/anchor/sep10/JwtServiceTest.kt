package org.stellar.anchor.sep10

import io.jsonwebtoken.MalformedJwtException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.Constants.Companion.TEST_JWT_SECRET
import org.stellar.anchor.config.AppConfig

internal class JwtServiceTest {
  companion object {
    const val TEST_ISS = "test_issuer"
    const val TEST_SUB = "test_sub"
    val TEST_IAT = System.currentTimeMillis() / 1000
    val TEST_EXP = System.currentTimeMillis() / 1000 + 900
    const val TEST_JTI = "test_jti"
    const val TEST_CLIENT_DOMAIN = "test_client_domain"
    const val TEST_REQUEST_ACCOUNT = "test_request_account"
  }

  @Test
  fun testcodec() {
    val appConfig = mockk<AppConfig>()
    every { appConfig.jwtSecretKey } returns TEST_JWT_SECRET

    val jwtService = JwtService(appConfig)
    val token =
      JwtToken.of(TEST_ISS, TEST_SUB, TEST_IAT, TEST_EXP, TEST_JTI, TEST_CLIENT_DOMAIN, null)
    val cipher = jwtService.encode(token)
    val dt = jwtService.decode(cipher)

    assertEquals(dt.iss, token.iss)
    assertEquals(dt.sub, token.sub)
    assertEquals(dt.iat, token.iat)
    assertEquals(dt.exp, token.exp)
    assertEquals(dt.jti, token.jti)
    assertEquals(dt.clientDomain, token.clientDomain)
    assertEquals(dt.account, token.sub)
    assertEquals(dt.transactionId, token.jti)
    assertEquals(dt.issuer, token.iss)
    assertEquals(dt.issuedAt, token.iat)
    assertEquals(dt.expiresAt, token.exp)
  }

  @Test
  fun testcodecRequestAccount() {
    val appConfig = mockk<AppConfig>()
    every { appConfig.jwtSecretKey } returns TEST_JWT_SECRET

    val jwtService = JwtService(appConfig)
    val token =
      JwtToken.of(
        TEST_ISS,
        TEST_SUB,
        TEST_IAT,
        TEST_EXP,
        TEST_JTI,
        TEST_CLIENT_DOMAIN,
        TEST_REQUEST_ACCOUNT
      )
    val cipher = jwtService.encode(token)
    val dt = jwtService.decode(cipher)

    assertEquals(dt.iss, token.iss)
    assertEquals(dt.sub, token.sub)
    assertEquals(dt.iat, token.iat)
    assertEquals(dt.exp, token.exp)
    assertEquals(dt.jti, token.jti)
    assertEquals(dt.clientDomain, token.clientDomain)
    assertEquals(dt.account, token.sub)
    assertEquals(dt.transactionId, token.jti)
    assertEquals(dt.issuer, token.iss)
    assertEquals(dt.issuedAt, token.iat)
    assertEquals(dt.expiresAt, token.exp)
    assertEquals(dt.requestedAccount, token.requestedAccount)
    assertEquals(dt.requestedAccount, TEST_REQUEST_ACCOUNT)
  }

  @Test
  fun testBadCipher() {
    val appConfig = mockk<AppConfig>()
    every { appConfig.jwtSecretKey } returns TEST_JWT_SECRET

    val jwtService = JwtService(appConfig)

    assertThrows<MalformedJwtException> { jwtService.decode("This is a bad cipher") }
  }
}
