package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.TestConstants
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET_SEP38_FORMAT
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.sep38.PojoSep38Quote
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.util.ExchangeAmountsCalculator.Amounts

class ExchangeAmountsCalculatorTest {
  companion object {
    val token = TestHelper.createSep10Jwt(TEST_ACCOUNT, TestConstants.TEST_MEMO)
  }

  private val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")

  @MockK(relaxed = true) lateinit var sep38QuoteStore: Sep38QuoteStore

  private lateinit var calculator: ExchangeAmountsCalculator

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    calculator = ExchangeAmountsCalculator(sep38QuoteStore)
  }

  private val usdcQuote =
    PojoSep38Quote().apply {
      sellAsset = TEST_ASSET_SEP38_FORMAT
      sellAmount = "100"
      buyAsset = "iso4217:USD"
      buyAmount = "98"
      fee =
        FeeDetails().apply {
          total = "2"
          asset = "iso4217:USD"
        }
    }

  @Test
  fun `test calculateFromQuote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote

    val result = calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "100")
    assertEquals(
      Amounts.builder()
        .amountIn("100")
        .amountInAsset(TEST_ASSET_SEP38_FORMAT)
        .amountOut("98")
        .amountOutAsset("iso4217:USD")
        .feeDetails(FeeDetails("2", "iso4217:USD"))
        .build(),
      result,
    )
  }

  @Test
  fun `test calculateFromQuote with invalid quote id`() {
    every { sep38QuoteStore.findByQuoteId(any()) } returns null
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote("id", assetService.getAsset("USDC"), "100")
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched sell amount`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "99")
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched sell asset`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("JPYC"), "100")
    }
  }

  @Test
  fun `test calculateFromQuote with bad quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote.apply { fee = null }
    assertThrows<SepValidationException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "100")
    }
  }

  @Test
  fun `test validateQuoteAgainstRequestInfo with mismatched buy asset`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote
    assertThrows<BadRequestException> {
      calculator.validateQuoteAgainstRequestInfo(
        quoteId,
        assetService.getAsset("USDC"),
        assetService.getAsset("JPYC"),
        "100",
      )
    }
  }
}
