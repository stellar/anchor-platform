package org.stellar.anchor.auth

import io.jsonwebtoken.MalformedJwtException
import io.mockk.mockk
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_NAME
import org.stellar.anchor.TestConstants.Companion.TEST_HOME_DOMAIN
import org.stellar.anchor.auth.JwtService.*
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep24MoreInfoUrlJwt
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.setupMock

internal class JwtServiceTest {
  companion object {
    const val TEST_ISS = "test_issuer"
    const val TEST_SUB = "test_sub"
    val TEST_IAT = System.currentTimeMillis() / 1000
    val TEST_EXP = System.currentTimeMillis() / 1000 + 900
    const val TEST_JTI = "test_jti"
    const val TEST_CLIENT_DOMAIN = "test_client_domain"
    const val TEST_ACCOUNT = "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO"
  }

  lateinit var secretConfig: SecretConfig
  lateinit var custodySecretConfig: CustodySecretConfig

  @BeforeEach
  fun setup() {
    secretConfig = mockk()
    custodySecretConfig = mockk()
    secretConfig.setupMock()
    custodySecretConfig.setupMock()
  }

  @Test
  fun `test apply Sep10Jwt encoding and decoding and make sure the original values are not changed`() {
    val jwtService = JwtService(secretConfig, custodySecretConfig)
    val token =
      Sep10Jwt.of(
        TEST_ISS,
        TEST_SUB,
        TEST_IAT,
        TEST_EXP,
        TEST_JTI,
        TEST_CLIENT_DOMAIN,
      ) as Sep10Jwt
    val cipher = jwtService.encode(token)
    val sep10Jwt = jwtService.decode(cipher, Sep10Jwt::class.java)

    assertEquals(sep10Jwt.iss, token.iss)
    assertEquals(sep10Jwt.sub, token.sub)
    assertEquals(sep10Jwt.iat, token.iat)
    assertEquals(sep10Jwt.exp, token.exp)
    assertEquals(sep10Jwt.jti, token.jti)
    assertEquals(sep10Jwt.clientDomain, token.clientDomain)
    assertEquals(sep10Jwt.account, token.sub)
    assertEquals(sep10Jwt.transactionId, token.jti)
    assertEquals(sep10Jwt.issuer, token.iss)
    assertEquals(sep10Jwt.issuedAt, token.iat)
    assertEquals(sep10Jwt.expiresAt, token.exp)
  }

  @Test
  fun `test apply Sep24MoreInfoUrlJwt encoding and decoding and make sure the original values are not changed`() {
    val jwtService = JwtService(secretConfig, custodySecretConfig)
    val token =
      Sep24MoreInfoUrlJwt(TEST_ACCOUNT, TEST_ISS, TEST_EXP, TEST_CLIENT_DOMAIN, TEST_CLIENT_NAME)
    val cipher = jwtService.encode(token)
    val sep24MoreInfoUrlJwt = jwtService.decode(cipher, Sep24MoreInfoUrlJwt::class.java)

    assertEquals(sep24MoreInfoUrlJwt.sub, token.sub)
    assertEquals(sep24MoreInfoUrlJwt.iss, token.iss)
    assertEquals(sep24MoreInfoUrlJwt.exp, token.exp)
    assertEquals(sep24MoreInfoUrlJwt.claims[CLIENT_DOMAIN], token.claims[CLIENT_DOMAIN])
    assertEquals(sep24MoreInfoUrlJwt.claims[CLIENT_NAME], token.claims[CLIENT_NAME])
  }

  @Test
  fun `test apply Sep24InteractiveUrlJwt encoding and decoding and make sure the original values are not changed`() {
    val jwtService = JwtService(secretConfig, custodySecretConfig)
    val token =
      Sep24InteractiveUrlJwt(
        TEST_ACCOUNT,
        TEST_ISS,
        TEST_EXP,
        TEST_CLIENT_DOMAIN,
        TEST_CLIENT_NAME,
        TEST_HOME_DOMAIN
      )
    val cipher = jwtService.encode(token)
    val sep24InteractiveUrlJwt = jwtService.decode(cipher, Sep24InteractiveUrlJwt::class.java)

    assertEquals(sep24InteractiveUrlJwt.sub, token.sub)
    assertEquals(sep24InteractiveUrlJwt.iss, token.iss)
    assertEquals(sep24InteractiveUrlJwt.exp, token.exp)
    assertEquals(sep24InteractiveUrlJwt.claims[CLIENT_DOMAIN], token.claims[CLIENT_DOMAIN])
    assertEquals(sep24InteractiveUrlJwt.claims[CLIENT_NAME], token.claims[CLIENT_NAME])
    assertEquals(sep24InteractiveUrlJwt.claims[HOME_DOMAIN], token.claims[HOME_DOMAIN])
  }

  @Test
  fun `make sure decoding bad cipher test throws an error`() {
    val jwtService = JwtService(secretConfig, custodySecretConfig)

    assertThrows<MalformedJwtException> {
      jwtService.decode("This is a bad cipher", Sep10Jwt::class.java)
    }
  }
}
