package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.web.util.UriComponentsBuilder
import org.stellar.anchor.api.callback.CustomerIntegration
import org.stellar.anchor.api.callback.PutCustomerRequest
import org.stellar.anchor.api.callback.PutCustomerResponse
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt
import org.stellar.anchor.config.ClientsConfig.ClientConfig
import org.stellar.anchor.config.ClientsConfig.ClientType
import org.stellar.anchor.config.ClientsConfig.ClientType.*
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.platform.config.PropertyClientsConfig
import org.stellar.anchor.platform.config.PropertySep24Config
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.service.SimpleInteractiveUrlConstructor.FORWARD_KYC_CUSTOMER_TYPE
import org.stellar.anchor.util.GsonUtils

@Suppress("UNCHECKED_CAST")
class SimpleInteractiveUrlConstructorTest {
  companion object {
    private val gson = GsonUtils.getInstance()
    @JvmStatic
    private fun constructorTestValues() =
      Stream.of(Arguments.of(SEP24_CONFIG_JSON_1, REQUEST_JSON_1, TXN_JSON_1))
  }

  @MockK(relaxed = true) private lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) private lateinit var clientsConfig: PropertyClientsConfig
  @MockK(relaxed = true) private lateinit var custodySecretConfig: CustodySecretConfig
  @MockK(relaxed = true) private lateinit var customerIntegration: CustomerIntegration

  private lateinit var jwtService: JwtService
  private lateinit var sep24Config: PropertySep24Config
  private lateinit var request: HashMap<String, String>
  private lateinit var txn: JdbcSep24Transaction

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { secretConfig.sep24InteractiveUrlJwtSecret } returns "sep24_jwt_secret"

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
        ClientType.CUSTODIAL,
        "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        null,
        null,
        false,
        null
      )

    jwtService = JwtService(secretConfig, custodySecretConfig)
    sep24Config = gson.fromJson(SEP24_CONFIG_JSON_1, PropertySep24Config::class.java)
    request = gson.fromJson(REQUEST_JSON_1, HashMap::class.java) as HashMap<String, String>
    txn = gson.fromJson(TXN_JSON_1, JdbcSep24Transaction::class.java)
  }

  @ParameterizedTest
  @MethodSource("constructorTestValues")
  fun `test correct constructor`(configJson: String, requestJson: String, txnJson: String) {
    val testConfig = gson.fromJson(configJson, PropertySep24Config::class.java)
    val testRequest = gson.fromJson(requestJson, HashMap::class.java)
    val testTxn = gson.fromJson(txnJson, JdbcSep24Transaction::class.java)

    val constructor =
      SimpleInteractiveUrlConstructor(clientsConfig, testConfig, customerIntegration, jwtService)

    var jwt =
      parseJwtFromUrl(constructor.construct(testTxn, testRequest as HashMap<String, String>?))
    testJwt(jwt)
    var claims = jwt.claims
    assertEquals("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO:1234", jwt.sub)
    assertEquals("lobstr.co", claims["client_domain"] as String)
    assertEquals("lobstr", claims["client_name"] as String)

    // Unknown client domain
    testTxn.sep10AccountMemo = null
    testTxn.clientDomain = "unknown.com"
    jwt = parseJwtFromUrl(constructor.construct(testTxn, testRequest))
    claims = jwt.claims
    testJwt(jwt)
    assertEquals("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO", jwt.sub)
    assertEquals("unknown.com", claims["client_domain"] as String)
    assertNull(claims["client_name"])

    // Custodial wallet
    testTxn.sep10Account = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    testTxn.sep10AccountMemo = "1234"
    testTxn.clientDomain = null
    jwt = parseJwtFromUrl(constructor.construct(testTxn, testRequest))
    claims = jwt.claims
    testJwt(jwt)
    assertEquals("GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP:1234", jwt.sub)
    assertNull(claims["client_domain"])
    assertEquals("some-wallet", claims["client_name"] as String)
  }

  @Test
  fun `test non-custodial wallet with missing client domain`() {
    val testConfig = gson.fromJson(SEP24_CONFIG_JSON_1, PropertySep24Config::class.java)
    val testRequest = gson.fromJson(REQUEST_JSON_1, HashMap::class.java)
    val testTxn = gson.fromJson(TXN_JSON_1, JdbcSep24Transaction::class.java)

    val constructor =
      SimpleInteractiveUrlConstructor(clientsConfig, testConfig, customerIntegration, jwtService)

    testTxn.clientDomain = null
    assertThrows<SepValidationException> {
      constructor.construct(testTxn, testRequest as HashMap<String, String>?)
    }
  }

  @Test
  fun `when kycFieldsForwarding is enabled, the customerIntegration should receive the kyc fields`() {
    val customerIntegration: CustomerIntegration = mockk()
    val capturedPutCustomerRequest = slot<PutCustomerRequest>()
    every { customerIntegration.putCustomer(capture(capturedPutCustomerRequest)) } returns
      PutCustomerResponse()
    val constructor =
      SimpleInteractiveUrlConstructor(clientsConfig, sep24Config, customerIntegration, jwtService)
    sep24Config.kycFieldsForwarding.isEnabled = true
    constructor.construct(txn, request as HashMap<String, String>?)
    assertEquals(capturedPutCustomerRequest.captured.type, FORWARD_KYC_CUSTOMER_TYPE)
    assertEquals(capturedPutCustomerRequest.captured.firstName, request.get("first_name"))
    assertEquals(capturedPutCustomerRequest.captured.lastName, request.get("last_name"))
    assertEquals(capturedPutCustomerRequest.captured.emailAddress, request.get("email_address"))
  }

  @Test
  fun `when kycFieldsForwarding is disabled, the customerIntegration should not receive the kyc fields`() {
    val customerIntegration: CustomerIntegration = mockk()
    val constructor =
      SimpleInteractiveUrlConstructor(clientsConfig, sep24Config, customerIntegration, jwtService)
    sep24Config.kycFieldsForwarding.isEnabled = false
    constructor.construct(txn, request as HashMap<String, String>?)
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  private fun parseJwtFromUrl(url: String?): Sep24InteractiveUrlJwt {
    val params = UriComponentsBuilder.fromUriString(url!!).build().queryParams
    val cipher = params["token"]!![0]
    return jwtService.decode(cipher, Sep24InteractiveUrlJwt::class.java)
  }

  private fun testJwt(jwt: Sep24InteractiveUrlJwt) {
    val claims = jwt.claims()
    assertEquals("txn_123", jwt.jti as String)
    assertTrue(Instant.ofEpochSecond(jwt.exp).isAfter(Instant.now()))

    // Transaction data
    val data = claims["data"] as Map<String, String>
    assertEquals("deposit", data["kind"] as String)
    assertEquals("100", data["amount"] as String)
    assertEquals("en", data["lang"] as String)
    assertEquals(
      "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      data["amount_in_asset"] as String
    )
    assertEquals("en", data["lang"] as String)
    assertNull(data["email_address"])

    // Name is in request but not in transaction. It must not be included.
    assertNull(data["name"])
  }
}

private const val SEP24_CONFIG_JSON_1 =
  """
{
  "enabled": false,
  "interactiveUrl": {
    "baseUrl": "http://localhost:8080/sep24/interactive",
    "jwtExpiration": 600,
    "txnFields": [
      "kind",
      "amount_in",
      "amount_in_asset",
      "asset_code"
    ]
  },
  "kycFieldsForwarding": {
    "enabled": false
  }
}
"""

private const val REQUEST_JSON_1 =
  """
{
  "name": "John Doe",
  "first_name": "John",
  "last_name": "Doe",
  "email_address": "john_doe@stellar.org",
  "lang": "en",
  "amount": "100"
}
"""

private const val TXN_JSON_1 =
  """
{
  "id": "123",
  "transaction_id": "txn_123",
  "status": "incomplete",
  "kind" : "deposit",
  "sep10_account": "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO",
  "sep10_account_memo": "1234",
  "amount_in": "100",
  "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
  "client_domain": "lobstr.co"
}  
"""
