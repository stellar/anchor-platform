package org.stellar.anchor.platform.integrationtest

import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.skyscreamer.jsonassert.JSONAssert
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.GetFeeRequest
import org.stellar.anchor.api.callback.GetRateRequest
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.api.sep.AssetInfo
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.callback.RestCustomerIntegration
import org.stellar.anchor.platform.callback.RestFeeIntegration
import org.stellar.anchor.platform.callback.RestRateIntegration
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.util.GsonUtils

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CallbackApiTests : AbstractIntegrationTests(TestConfig()) {

  companion object {
    private const val JWT_EXPIRATION_MILLISECONDS: Long = 10000
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USD =
      "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  }

  private val sep12Client: Sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token.token)

  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()

  private val platformToAnchorJwtService =
    JwtService(
      config.env["secret.sep6.more_info_url.jwt_secret"],
      config.env["secret.sep10.jwt_secret"]!!,
      config.env["secret.sep24.interactive_url.jwt_secret"]!!,
      config.env["secret.sep24.more_info_url.jwt_secret"]!!,
      config.env["secret.callback_api.auth_secret"]!!,
      config.env["secret.platform_api.auth_secret"]!!,
      null,
    )

  private val authHelper =
    AuthHelper.forJwtToken("Authorization", platformToAnchorJwtService, JWT_EXPIRATION_MILLISECONDS)

  private val gson: Gson = GsonUtils.getInstance()
  private val mockAssetService = mockk<AssetService>()

  private val rci =
    RestCustomerIntegration(config.env["reference.server.url"]!!, httpClient, authHelper, gson)
  private val rriClient =
    RestRateIntegration(
      config.env["reference.server.url"]!!,
      httpClient,
      authHelper,
      gson,
      mockAssetService,
    )
  private val rfiClient =
    RestFeeIntegration(config.env["reference.server.url"]!!, httpClient, authHelper, gson)

  @BeforeAll
  fun setup() {
    val usdc = AssetInfo()
    usdc.schema = AssetInfo.Schema.stellar
    usdc.code = "USDC"
    usdc.issuer = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    usdc.significantDecimals = 4

    val usd = AssetInfo()
    usd.schema = AssetInfo.Schema.iso4217
    usd.code = "USD"
    usd.significantDecimals = 2

    every {
      mockAssetService.getAssetByName(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      )
    } returns usdc
    every { mockAssetService.getAssetByName("iso4217:USD") } returns usd
    every { mockAssetService.getAssetByName(null) } returns null
  }

  @Test
  fun testCustomerIntegration() {
    assertThrows<NotFoundException> {
      rci.getCustomer(GetCustomerRequest.builder().id("1").build())
    }
  }

  @Test
  fun testRate_indicativePrice() {
    val result =
      rriClient.getRate(
        GetRateRequest.builder()
          .type(GetRateRequest.Type.INDICATIVE)
          .sellAsset(FIAT_USD)
          .sellAmount("100")
          .buyAsset(STELLAR_USD)
          .build()
      )
    Assertions.assertNotNull(result)
    val wantBody =
      """{
      "rate":{
        "price":"1.0200002473",
        "sell_amount": "100",
        "buy_amount": "97.0588",
        "fee": {
          "total": "1.00",
          "asset": "$FIAT_USD",
          "details": [
            {
              "name": "Sell fee",
              "description": "Fee related to selling the asset.",
              "amount": "1.00"
            }
          ]
        }
      }
    }"""
        .trimMargin()
    JSONAssert.assertEquals(wantBody, org.stellar.anchor.platform.gson.toJson(result), true)
  }

  @Test
  fun testGetFee() {
    // Create sender customer
    val senderCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    val senderCustomer = sep12Client.putCustomer(senderCustomerRequest)

    // Create receiver customer
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = sep12Client.putCustomer(receiverCustomerRequest)

    val result =
      rfiClient.getFee(
        GetFeeRequest.builder()
          .sendAmount("10")
          .sendAsset("USDC")
          .receiveAsset("USDC")
          .senderId(senderCustomer!!.id)
          .receiverId(receiverCustomer!!.id)
          .clientId("<client-id>")
          .build()
      )

    Assertions.assertNotNull(result)
    JSONAssert.assertEquals(
      org.stellar.anchor.platform.gson.toJson(result),
      """{
          "fee": {
            "asset": "USDC",
            "amount": "0.30"
          }
        }""",
      true,
    )
  }
}
