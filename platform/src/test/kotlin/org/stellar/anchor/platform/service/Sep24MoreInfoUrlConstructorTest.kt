package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.util.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.web.util.UriComponentsBuilder
import org.stellar.anchor.LockStatic
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep24MoreInfoUrlJwt
import org.stellar.anchor.config.ClientsConfig.ClientConfig
import org.stellar.anchor.config.ClientsConfig.ClientType.CUSTODIAL
import org.stellar.anchor.config.ClientsConfig.ClientType.NONCUSTODIAL
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.platform.config.MoreInfoUrlConfig
import org.stellar.anchor.platform.config.PropertyClientsConfig
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.utils.setupMock
import org.stellar.anchor.util.GsonUtils

@Execution(ExecutionMode.SAME_THREAD)
class Sep24MoreInfoUrlConstructorTest {
  companion object {
    private val gson = GsonUtils.getInstance()
    private const val LANG = "en"
  }

  @MockK(relaxed = true) private lateinit var assetService: AssetService
  @MockK(relaxed = true) private lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) private lateinit var clientsConfig: PropertyClientsConfig
  @MockK(relaxed = true) private lateinit var custodySecretConfig: CustodySecretConfig

  private lateinit var jwtService: JwtService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    secretConfig.setupMock()

    val clientConfig =
      ClientConfig(
        "lobstr",
        NONCUSTODIAL,
        "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO",
        "lobstr.co",
        "https://callback.lobstr.co/api/v2/anchor/callback",
        false,
        null
      )
    every { clientsConfig.getClientConfigByDomain(any()) } returns null
    every { clientsConfig.getClientConfigByDomain(clientConfig.domain) } returns clientConfig
    every { clientsConfig.getClientConfigBySigningKey(clientConfig.signingKey) } returns
      clientConfig
    every {
      clientsConfig.getClientConfigBySigningKey(
        "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      )
    } returns
      ClientConfig(
        "some-wallet",
        CUSTODIAL,
        "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        null,
        null,
        false,
        null
      )

    jwtService = JwtService(secretConfig, custodySecretConfig)
  }

  @Test
  @LockStatic([Calendar::class])
  fun `test correct config`() {
    val config = gson.fromJson(SIMPLE_CONFIG_JSON, MoreInfoUrlConfig::class.java)
    val constructor = Sep24MoreInfoUrlConstructor(assetService, clientsConfig, config, jwtService)
    val txn = gson.fromJson(TXN_JSON, JdbcSep24Transaction::class.java)
    val url = constructor.construct(txn, LANG)

    val params = UriComponentsBuilder.fromUriString(url).build().queryParams
    val cipher = params["token"]!![0]

    val jwt = jwtService.decode(cipher, Sep24MoreInfoUrlJwt::class.java)
    val claims = jwt.claims()
    testJwt(jwt)
    assertEquals("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO:1234", jwt.sub)
    assertEquals("lobstr.co", claims["client_domain"])
    assertEquals("lobstr", claims["client_name"])
  }

  @Test
  @LockStatic([Calendar::class])
  fun `test unknown client domain`() {
    val config = gson.fromJson(SIMPLE_CONFIG_JSON, MoreInfoUrlConfig::class.java)
    val constructor = Sep24MoreInfoUrlConstructor(assetService, clientsConfig, config, jwtService)
    val txn = gson.fromJson(TXN_JSON, JdbcSep24Transaction::class.java)
    txn.clientDomain = "unknown.com"
    txn.sep10AccountMemo = null

    val url = constructor.construct(txn, LANG)
    val params = UriComponentsBuilder.fromUriString(url).build().queryParams
    val cipher = params["token"]!![0]

    val jwt = jwtService.decode(cipher, Sep24MoreInfoUrlJwt::class.java)
    val claims = jwt.claims()
    testJwt(jwt)
    assertEquals("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO", jwt.sub)
    assertEquals("unknown.com", claims["client_domain"])
  }

  @Test
  @LockStatic([Calendar::class])
  fun `test custodial wallet`() {
    val config = gson.fromJson(SIMPLE_CONFIG_JSON, MoreInfoUrlConfig::class.java)
    val constructor = Sep24MoreInfoUrlConstructor(assetService, clientsConfig, config, jwtService)
    val txn = gson.fromJson(TXN_JSON, JdbcSep24Transaction::class.java)
    txn.sep10Account = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    txn.clientDomain = null

    val url = constructor.construct(txn, LANG)

    val params = UriComponentsBuilder.fromUriString(url).build().queryParams
    val cipher = params["token"]!![0]

    val jwt = jwtService.decode(cipher, Sep24MoreInfoUrlJwt::class.java)
    val claims = jwt.claims()
    testJwt(jwt)
    assertEquals("GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP:1234", jwt.sub)
    assertEquals(null, claims["client_domain"])
    assertEquals("some-wallet", claims["client_name"])
  }

  @Test
  @LockStatic([Calendar::class])
  fun `test non-custodial wallet with missing client domain`() {
    val config = gson.fromJson(SIMPLE_CONFIG_JSON, MoreInfoUrlConfig::class.java)
    val constructor = Sep24MoreInfoUrlConstructor(assetService, clientsConfig, config, jwtService)
    val txn = gson.fromJson(TXN_JSON, JdbcSep24Transaction::class.java)
    txn.clientDomain = null

    assertThrows<SepValidationException> { constructor.construct(txn, LANG) }
  }

  private fun testJwt(jwt: Sep24MoreInfoUrlJwt) {
    assertEquals("txn_123", jwt.jti as String)
    Assertions.assertTrue(Instant.ofEpochSecond(jwt.exp).isAfter(Instant.now()))

    val data = jwt.claims["data"] as Map<String, String>
    assertEquals(LANG, data["lang"] as String)
  }
}

private const val SIMPLE_CONFIG_JSON =
  """
{
  "baseUrl": "http://localhost:8080/sep24/more_info_url",
  "jwtExpiration": 600,
  "txnFields": [
    "kind",
    "status"
  ]
}
"""

private const val TXN_JSON =
  """
{
  "id": "123",
  "transaction_id": "txn_123",
  "status": "incomplete",
  "kind" : "deposit",
  "amount_in": "100",
  "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
  "sep10_account": "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO",
  "sep10_account_memo": "1234",
  "client_domain": "lobstr.co"
}  
"""
