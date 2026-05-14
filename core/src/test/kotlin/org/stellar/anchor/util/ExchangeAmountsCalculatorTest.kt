package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.time.Clock
import java.time.Instant
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
    val token = TestHelper.createWebAuthJwt(TEST_ACCOUNT, TestConstants.TEST_MEMO)
  }

  private val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")

  @MockK(relaxed = true) lateinit var sep38QuoteStore: Sep38QuoteStore

  private lateinit var calculator: ExchangeAmountsCalculator

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    calculator = ExchangeAmountsCalculator(sep38QuoteStore, Clock.systemUTC())
  }

  private fun usdcQuote() =
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
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()

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
  fun `test calculateFromQuote with expired quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply {
        expiresAt = Instant.parse("2000-01-01T00:00:00Z")
        fee = FeeDetails("2", "iso4217:USD")
      }

    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "100")
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched sell amount`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "99")
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched sell asset`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("JPYC"), "100")
    }
  }

  @Test
  fun `test calculateFromQuote with bad quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote().apply { fee = null }
    assertThrows<SepValidationException> {
      calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "100")
    }
  }

  @Test
  fun `test validateQuoteAgainstRequestInfo with mismatched buy asset`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.validateQuoteAgainstRequestInfo(
        quoteId,
        assetService.getAsset("USDC"),
        assetService.getAsset("JPYC"),
        "100",
      )
    }
  }

  @Test
  fun `test calculateFromQuote rejects already-bound quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { transactionId = "existing-txn-id" }
    val ex =
      assertThrows<BadRequestException> {
        calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "100")
      }
    assert(ex.message!!.contains("has already been used"))
  }

  @Test
  fun `test validateQuoteAgainstRequestInfo passes for unbound quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    val result =
      calculator.validateQuoteAgainstRequestInfo(
        quoteId,
        assetService.getAsset("USDC"),
        null,
        "100",
      )
    assertEquals(TEST_ASSET_SEP38_FORMAT, result.sellAsset)
  }

  @Test
  fun `test bindQuoteToTransaction succeeds when unbound`() {
    every { sep38QuoteStore.bindToTransaction("id", "txn1") } returns true
    calculator.bindQuoteToTransaction("id", "txn1")
  }

  @Test
  fun `test bindQuoteToTransaction throws when already bound`() {
    every { sep38QuoteStore.bindToTransaction("id", "txn2") } returns false
    val ex = assertThrows<BadRequestException> { calculator.bindQuoteToTransaction("id", "txn2") }
    assert(ex.message!!.contains("has already been used"))
  }

  @Test
  fun `test calculateFromQuote rejects T2 after T1 is cancelled`() {
    val quoteId = "q-cancel"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { transactionId = "T1-cancelled" }
    val ex =
      assertThrows<BadRequestException> {
        calculator.calculateFromQuote(quoteId, assetService.getAsset("USDC"), "100")
      }
    assert(ex.message!!.contains("has already been used"))
  }

  @Test
  fun `test bindQuoteToTransaction rejects T2 after T1 is cancelled`() {
    every { sep38QuoteStore.bindToTransaction("q-cancel", "T2") } returns false
    val ex =
      assertThrows<BadRequestException> { calculator.bindQuoteToTransaction("q-cancel", "T2") }
    assert(ex.message!!.contains("has already been used"))
  }
}
