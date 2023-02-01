package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.platform.config.PropertySep24Config
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.util.GsonUtils

@Suppress("UNCHECKED_CAST")
class SimpleInteractiveUrlConstructorTest {
  companion object {
    private val gson = GsonUtils.getInstance()
  }

  @MockK(relaxed = true) private lateinit var jwtService: JwtService
  private lateinit var sep10Jwt: Sep10Jwt
  private lateinit var sep9Fields: HashMap<*, *>
  private lateinit var txn: JdbcSep24Transaction

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    every { jwtService.encode(any()) } returns "mock_token"

    sep10Jwt = Sep10Jwt()
    sep9Fields = gson.fromJson(SEP9_FIELDS_JSON, HashMap::class.java)
    txn = gson.fromJson(TXN_JSON, JdbcSep24Transaction::class.java)
  }

  @Test
  fun `test correct config`() {
    val config = gson.fromJson(SIMPLE_CONFIG_JSON, PropertySep24Config.InteractiveUrlConfig::class.java)
    val constructor = SimpleInteractiveUrlConstructor(config, jwtService)
    val url = constructor.construct(sep10Jwt, txn, "en", sep9Fields as HashMap<String, String>?)
    assertEquals(WANTED_TEST_URL, url)
  }
}

private const val SIMPLE_CONFIG_JSON =
    """
{
  "baseUrl": "http://localhost:8080/sep24/interactive",
  "txnFields": [
    "kind",
    "amount_in",
    "amount_in_asset",
    "asset_code"
  ]
}
"""

private const val SEP9_FIELDS_JSON =
    """
{
  "name": "John Doe",
  "email": "john_doe@stellar.org"
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
  "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
}  
"""

private const val WANTED_TEST_URL =
    """http://localhost:8080/sep24/interactive?transaction_id=txn_123&token=mock_token&lang=en&name=John+Doe&email=john_doe%40stellar.org&kind=deposit&amountIn=100&amountInAsset=stellar%3AUSDC%3AGDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"""
