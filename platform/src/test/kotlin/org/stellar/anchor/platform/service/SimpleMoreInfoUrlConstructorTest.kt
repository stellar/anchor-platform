package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.JwtToken
import org.stellar.anchor.platform.config.PropertySep24Config
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.util.GsonUtils

class SimpleMoreInfoUrlConstructorTest {
  companion object {
    private val gson = GsonUtils.getInstance()
  }

  @MockK(relaxed = true) private lateinit var jwtService: JwtService
  lateinit var jwtToken: JwtToken
  lateinit var txn: JdbcSep24Transaction

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    every { jwtService.encode(any()) } returns "mock_token"

    jwtToken = JwtToken()
    txn = gson.fromJson(txnJson, JdbcSep24Transaction::class.java)
  }

  @Test
  fun `test correct config`() {
    val config =
      gson.fromJson(simpleConfig, PropertySep24Config.SimpleMoreInfoUrlConfig::class.java)
    val constructor = SimpleMoreInfoUrlConstructor(config, jwtService)
    val url = constructor.construct(txn)
    assertEquals(expectedUrl, url)
  }
}

private const val simpleConfig =
  """
{
  "baseUrl": "http://localhost:8080/sep24/more_info_url",
  "txnFields": [
    "kind",
    "status"
  ]
}
"""

private const val txnJson =
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

private const val expectedUrl =
  """http://localhost:8080/transaction-status?transaction_id=txn_123&token=mock_token"""
