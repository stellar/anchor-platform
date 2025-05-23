package org.stellar.anchor.auth

import io.jsonwebtoken.MalformedJwtException
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

  @ValueSource(classes = [Sep10Jwt::class, Sep45Jwt::class])
  @ParameterizedTest
  fun `test apply WebAuthJwt encoding and decoding and make sure the original values are not changed`(
    clazz: Class<out WebAuthJwt>
  ) {
    val jwtService = JwtService(secretConfig, custodySecretConfig)
    val constructor =
      clazz.getConstructor(
        String::class.java,
        String::class.java,
        Long::class.java,
        Long::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
      )
    val token =
      constructor.newInstance(
        TEST_ISS,
        TEST_SUB,
        TEST_IAT,
        TEST_EXP,
        TEST_JTI,
        TEST_CLIENT_DOMAIN,
        null,
      ) as WebAuthJwt
    val cipher = jwtService.encode(token)
    val webAuthJwt = jwtService.decode(cipher, clazz)

    assertEquals(webAuthJwt.iss, token.iss)
    assertEquals(webAuthJwt.sub, token.sub)
    assertEquals(webAuthJwt.iat, token.iat)
    assertEquals(webAuthJwt.exp, token.exp)
    assertEquals(webAuthJwt.jti, token.jti)
    assertEquals(webAuthJwt.clientDomain, token.clientDomain)
    assertEquals(webAuthJwt.account, token.sub)
    assertEquals(webAuthJwt.transactionId, token.jti)
    assertEquals(webAuthJwt.issuer, token.iss)
    assertEquals(webAuthJwt.issuedAt, token.iat)
    assertEquals(webAuthJwt.expiresAt, token.exp)
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
        TEST_HOME_DOMAIN,
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
