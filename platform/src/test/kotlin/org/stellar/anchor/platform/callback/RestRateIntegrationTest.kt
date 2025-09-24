package org.stellar.anchor.platform.callback

import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.math.BigDecimal
import java.time.Instant
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.callback.GetRateRequest
import org.stellar.anchor.api.callback.GetRateRequest.Type.from
import org.stellar.anchor.api.callback.GetRateResponse
import org.stellar.anchor.api.exception.ServerErrorException
import org.stellar.anchor.api.sep.AssetInfo
import org.stellar.anchor.api.sep.AssetInfo.Schema.iso4217
import org.stellar.anchor.api.sep.AssetInfo.Schema.stellar
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.platform.callback.RestRateIntegration.withinRoundingError
import org.stellar.anchor.util.GsonUtils

class RestRateIntegrationTest {
  private val assetService = mockk<AssetService>()
  private val gson: Gson = GsonUtils.getInstance()
  private var usdAssetInfo = AssetInfo()
  private var usdcAssetInfo = AssetInfo()
  private val rateIntegration =
    RestRateIntegration("http://localhost/callback", null, null, gson, assetService)
  private var request: GetRateRequest = GetRateRequest()
  private var rateResponseWithFee: GetRateResponse = GetRateResponse()
  private var rateResponseWithoutFee: GetRateResponse = GetRateResponse()

  @BeforeEach
  fun setUp() {
    // Set up USDC asset info
    usdAssetInfo.code = "USD"
    usdAssetInfo.schema = iso4217
    usdAssetInfo.significantDecimals = 2

    // Set up USD asset info
    usdcAssetInfo.schema = stellar
    usdcAssetInfo.code = "USDC"
    usdcAssetInfo.issuer = "GABCD"
    usdcAssetInfo.significantDecimals = 7

    // Set up asset service
    every { assetService.getAssetByName("iso4217:USD") } returns usdAssetInfo
    every { assetService.getAssetByName("stellar:USDC:GABCD") } returns usdcAssetInfo
    every { assetService.getAssetByName("unknown") } returns null
    every { assetService.getAssetByName(null) } returns null

    // Set up request and response
    request =
      gson.fromJson(
        """
        {
          "type": "indicative",
          "sell_asset": "iso4217:USD",
          "sell_amount": "100",
          "buy_asset": "stellar:USDC:GABCD"
        }
        """,
        GetRateRequest::class.java,
      )

    rateResponseWithFee =
      gson.fromJson(
        """
        {
          "rate": {
            "id": 1,
            "price": "1.05",
            "sell_amount": "100",
            "buy_amount": "94.29",
            "fee": {
              "total": "1.00",
              "asset": "iso4217:USD",
              "details": [
                {
                  "name": "Sell fee #1",
                  "description": "Fee related to selling the asset #1.",
                  "amount": "0.70"
                },
                {
                  "name": "Sell fee #2",
                  "description": "Fee related to selling the asset #2.",
                  "amount": "0.30"
                }
              ]
            }
          }
        }
        """,
        GetRateResponse::class.java,
      )

    rateResponseWithoutFee =
      gson.fromJson(
        """
      {
        "rate": {
            "price": "4000",
            "sell_amount": "8000",
            "buy_amount": "2.00"
        }
      }
      """,
        GetRateResponse::class.java,
      )
  }

  @Test
  fun `test getRate when the callback response does not provide a fee`() {
    val mockResponse = mockk<Response>()
    val mockResponseBody = mockk<ResponseBody>()
    val spyRateIntegration = spyk(rateIntegration)
    every { mockResponse.body } returns mockResponseBody
    every { mockResponse.code } returns 200
    every { mockResponse.close() } returns Unit
    every { mockResponseBody.string() } returns gson.toJson(rateResponseWithoutFee)
    every { spyRateIntegration.invokeGetRateRequest(any(), any()) } returns mockResponse

    request.sellAmount = "8000"
    val rate = spyRateIntegration.getRate(request)
    assertNotNull(rate.rate.fee)
    assertEquals(BigDecimal(rate.rate.fee?.total).compareTo(BigDecimal.ZERO), 0)
  }

  @ParameterizedTest
  @ValueSource(strings = ["indicative", "firm"])
  fun `test INDICATIVE and FIRM validateRateResponse with fee`(type: String) {
    request.type = from(type)
    rateResponseWithFee.rate.id = "1234"
    rateResponseWithFee.rate.expiresAt = Instant.now()

    // The fee is in sell_asset
    rateResponseWithFee.rate.fee?.asset = usdAssetInfo.sep38AssetName
    rateResponseWithFee.rate.sellAmount = "100.00"
    rateResponseWithFee.rate.buyAmount = "94.29"
    rateIntegration.validateRateResponse(request, rateResponseWithFee)

    // The fee is in buy_asset
    rateResponseWithFee.rate.fee?.asset = usdcAssetInfo.sep38AssetName
    rateResponseWithFee.rate.sellAmount = "100.00"
    rateResponseWithFee.rate.buyAmount = "94.24"
    rateIntegration.validateRateResponse(request, rateResponseWithFee)
  }

  @ParameterizedTest
  @ValueSource(strings = ["indicative", "firm"])
  fun `test INDICATIVE and FIRM validateRateResponse without fee`(type: String) {
    request.type = from(type)
    request.sellAmount = "8000"
    rateResponseWithoutFee.rate.id = "1234"
    rateResponseWithoutFee.rate.expiresAt = Instant.now()
    rateIntegration.validateRateResponse(request, rateResponseWithoutFee)
  }

  @ParameterizedTest
  @ValueSource(strings = ["indicative", "firm"])
  fun `test bad sell and buy amounts without fee`(type: String) {
    request.type = from(type)
    request.sellAmount = "8000"
    rateResponseWithoutFee.rate.id = "1234"
    rateResponseWithoutFee.rate.expiresAt = Instant.now()
    rateResponseWithoutFee.rate.price = "4001"
    val ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithoutFee)
      }
    assertEquals(
      "'rate.sell_amount' (8000) is not within the expected (8002.00[=4001*2.00]) ('price * buy_amount') in the GET /rate response",
      ex.message
    )
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "-1.0,is missing or not a positive number in the GET /rate response",
        "-1.00000001,is missing or not a positive number in the GET /rate response",
        "-2000,is missing or not a positive number in the GET /rate response",
        "null,is missing or not a positive number in the GET /rate response",
        "1.00000001,has incorrect number of significant decimals",
      ]
  )
  fun `test bad sell and buy amounts`(badAmount: String?, errorMessage: String) {
    // Bad sell amount
    request.sellAmount = badAmount
    rateResponseWithFee.rate.sellAmount = badAmount
    rateResponseWithFee.rate.buyAmount = "94.29"
    var ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    //    assertEquals("'rate.sell_amount' ${errorMessage.trim()}", ex.message)
    assertTrue(ex.message!!.contains(errorMessage))

    // Bad buy amount
    request.sellAmount = "100"
    rateResponseWithFee.rate.sellAmount = "100"
    rateResponseWithFee.rate.buyAmount = badAmount
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertTrue(ex.message!!.contains(errorMessage))
  }

  @Test
  fun `test mis-matched sell_amount and buy_amount`() {
    // Bad sell amount
    request.sellAmount = "100.02"
    rateResponseWithFee.rate.sellAmount = "100.02" // expect 100.01
    rateResponseWithFee.rate.buyAmount = "94.29"
    var ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.sell_amount' (100.02) is not within the rounding error of the expected (100.0045[=1.05*94.29+1.00]) ('price * buy_amount + fee') in the GET /rate response",
      ex.message,
    )

    request.sellAmount = "100.00"
    rateResponseWithFee.rate.sellAmount = "100.00"
    rateResponseWithFee.rate.buyAmount = "94.00"

    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.sell_amount' (100.00) is not within the rounding error of the expected (99.7000[=1.05*94.00+1.00]) ('price * buy_amount + fee') in the GET /rate response",
      ex.message,
    )
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "-1.00, true, is missing or a negative number in the GET /rate response",
        "0.00, true, 'rate.sell_amount' (100) is not within the rounding error of the expected (99.0045[=1.05*94.29+0.00]) ('price * buy_amount + fee') in the GET /rate response",
        "1.00, false, null",
      ]
  )
  fun `test fee total`(total: String?, hasError: Boolean, errorMessage: String) {
    rateResponseWithFee.rate.fee?.total = total
    rateResponseWithFee.rate.fee?.details = null
    if (hasError) {
      val ex =
        assertThrows<ServerErrorException> {
          rateIntegration.validateRateResponse(request, rateResponseWithFee)
        }
      assertTrue(ex.message!!.contains(errorMessage))
    } else {
      rateIntegration.validateRateResponse(request, rateResponseWithFee)
    }
  }

  @Test
  fun `test bad fee total and asset`() {
    rateResponseWithFee.rate.fee?.total = null
    var ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.total' is missing or a negative number in the GET /rate response",
      ex.message,
    )

    rateResponseWithFee.rate.fee?.total = "1.00"
    rateResponseWithFee.rate.fee?.asset = "unknown"
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.asset' is missing or not a valid asset in the GET /rate response",
      ex.message,
    )

    rateResponseWithFee.rate.fee?.total = "1.00000001"
    rateResponseWithFee.rate.fee?.asset = "iso4217:USD"
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.total' (1.00000001) has incorrect number of significant decimals (expected: 2) in the GET /rate response",
      ex.message,
    )
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1])
  fun `test bad fee details without name or amount`(feeIndex: Int) {
    rateResponseWithFee.rate.fee.details[feeIndex].name = null
    var ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.details[?].name' is missing in the GET /rate response",
      ex.message,
    )

    rateResponseWithFee.rate.fee.details[feeIndex].name = "Sell fee"
    rateResponseWithFee.rate.fee.details[feeIndex].amount = null
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.details[?].amount' is missing or not a positive number in the GET /rate response",
      ex.message,
    )

    rateResponseWithFee.rate.fee.details[feeIndex].name = "Sell fee"
    rateResponseWithFee.rate.fee.details[feeIndex].amount = "0.71" // Expect 0.70
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertTrue(ex.message!!.contains("is not equal to the sum of fees "))
  }

  @Test
  fun `test bad decimals from the response`() {
    // Bad sell amount
    request.sellAmount = "106.00000001"
    rateResponseWithFee.rate.sellAmount = "106.00000001"
    rateResponseWithFee.rate.buyAmount = "94.29"
    rateResponseWithFee.rate.fee.details[0].amount = "0.7"
    var ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.sell_amount' (106.00000001) has incorrect number of significant decimals (expected: 2) in the GET /rate response",
      ex.message,
    )

    // Bad buy amount
    request.sellAmount = "100"
    rateResponseWithFee.rate.sellAmount = "100"
    rateResponseWithFee.rate.buyAmount = "94.28571428571429"
    rateResponseWithFee.rate.fee.details[0].amount = "0.7"
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.buy_amount' (94.28571428571429) has incorrect number of significant decimals (expected: 7) in the GET /rate response",
      ex.message,
    )

    // Bad fee amount
    rateResponseWithFee.rate.sellAmount = "100"
    rateResponseWithFee.rate.buyAmount = "94.29"
    rateResponseWithFee.rate.fee.details[0].amount = "0.00007"
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.details[?].amount' has incorrect number of significant decimals in the GET /rate response",
      ex.message,
    )
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        // Round down
        "1.014,1.01,2,true",
        "1.014, 1.01,3,false",
        "1.0104, 1.01,3,true",
        // Round up
        "1.014,1.02,2,true",
        // Ceil
        "1.001, 1.01,2,true",
        "1.001,1.01, 3, false",
        // Floor
        "1.019, 1.01, 2, true",
        "1.019, 1.01, 3, false",

        // sell has 2, buy has 5. calculated sell amount is 4.99999. returned sell amount is
        // 5.00
        "4.99999,5.00,2,true",
        "4.99999,5.00,5,false",

        // sell has 2, buy has 5. calculated sell amount is 4.99999. returned sell amount is
        // 5.00
        "4.99999,5.00,2,true",
        "4.99999,5.00,5,false",

        // Test cases
        "4.99999999984375,5,2,true",
        "4.99999999984375,5,3,true",
        "4.99999999984375,5,4,true",
        "4.99999999984375,5,5,true",
        "4.99999999984375,5,8,true",
        "4.99999999984375,5,9,true",
        "4.99999999984375,5,10,false",
      ]
  )
  fun `test equals in scale withing rounding errors`(
    amount: String,
    expected: String,
    scale: String,
    shouldAllow: String,
  ) {
    assertEquals(
      shouldAllow.toBoolean(),
      withinRoundingError(BigDecimal(amount), BigDecimal(expected), scale.toInt()),
    )
  }

  @Test
  fun `test 0 fee with details`() {
    rateResponseWithFee.rate.fee.total = "0.00"
    rateResponseWithFee.rate.fee.details[0].amount = "0.00"
    rateResponseWithFee.rate.fee.details[1].amount = "0.00"
    val ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithFee)
      }
    assertEquals(
      "'rate.fee.details' must be empty or not-existent when 'rate.fee.total' is zero in the GET /rate response",
      ex.message
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["indicative", "firm"])
  fun `test failure when sell_amount of the response does not match that of the request`(
    type: String
  ) {
    request.type = from(type)
    rateResponseWithoutFee.rate.id = "1234"
    rateResponseWithoutFee.rate.expiresAt = Instant.now()
    rateResponseWithoutFee.rate.sellAmount = "100.01"
    var ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithoutFee)
      }
    assertEquals(
      "'rate.sell_amount' (100.01) is not equal to the requested 'sell_amount' (100) in the GET /rate response",
      ex.message
    )

    request.sellAmount = null
    request.sellAsset = null
    request.buyAmount = "94.29"
    ex =
      assertThrows<ServerErrorException> {
        rateIntegration.validateRateResponse(request, rateResponseWithoutFee)
      }
    assertEquals(
      "'rate.buy_amount' (2.00) is not equal to the requested 'buy_amount' (94.29) in the GET /rate response",
      ex.message
    )
  }
}
