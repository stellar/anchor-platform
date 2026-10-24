package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.TestConstants
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET_SEP38_FORMAT
import org.stellar.anchor.TestHelper
import org.stellar.anchor.TestHelper.Companion.createMuxedWebAuthJwt
import org.stellar.anchor.TestHelper.Companion.createWebAuthJwt
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.auth.WebAuthJwt
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
      creatorAccountId = TEST_ACCOUNT
      creatorMemo = TestConstants.TEST_MEMO
      creatorMemoType = "id"
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

    val result =
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        null,
        null
      )
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
      calculator.calculateFromQuote(
        "id",
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        null,
        null
      )
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
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        null,
        null
      )
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched sell amount`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "99",
        null,
        null
      )
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched sell asset`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("JPYC"),
        null,
        "100",
        null,
        null
      )
    }
  }

  @Test
  fun `test calculateFromQuote with bad quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote().apply { fee = null }
    assertThrows<SepValidationException> {
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        null,
        null
      )
    }
  }

  @Test
  fun `test validateQuoteAgainstRequestInfo with mismatched buy asset`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.validateQuoteAgainstRequestInfo(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        assetService.getAsset("JPYC"),
        "100",
      )
    }
  }

  @Test
  fun `test calculateFromQuote with mismatched buy asset throws`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    assertThrows<BadRequestException> {
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        assetService.getAsset("JPYC"),
        "100",
        null,
        null,
      )
    }
  }

  @Test
  fun `test calculateFromQuote with matching buy asset succeeds`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()

    val result =
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        assetService.getAsset("USD"),
        "100",
        null,
        null,
      )
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
  fun `test calculateFromQuote rejects already-bound quote`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { transactionId = "existing-txn-id" }
    val ex =
      assertThrows<BadRequestException> {
        calculator.calculateFromQuote(
          quoteId,
          ownerToken,
          assetService.getAsset("USDC"),
          null,
          "100",
          null,
          null,
        )
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
        ownerToken,
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
        calculator.calculateFromQuote(
          quoteId,
          ownerToken,
          assetService.getAsset("USDC"),
          null,
          "100",
          null,
          null,
        )
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

  @Test
  fun `test calculateFromQuote rejects a sell delivery method mismatch`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { sellDeliveryMethod = "WIRE" }
    val ex =
      assertThrows<BadRequestException> {
        calculator.calculateFromQuote(
          quoteId,
          ownerToken,
          assetService.getAsset("USDC"),
          null,
          "100",
          "SWIFT",
          null
        )
      }
    assertEquals(
      "funding_method(SWIFT) does not match quote sell delivery method(WIRE)",
      ex.message,
    )
  }

  @Test
  fun `test calculateFromQuote rejects a buy delivery method mismatch`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { buyDeliveryMethod = "WIRE" }
    val ex =
      assertThrows<BadRequestException> {
        calculator.calculateFromQuote(
          quoteId,
          ownerToken,
          assetService.getAsset("USDC"),
          null,
          "100",
          null,
          "bank_account",
        )
      }
    assertEquals(
      "funding_method(bank_account) does not match quote buy delivery method(WIRE)",
      ex.message,
    )
  }

  @Test
  fun `test calculateFromQuote accepts a matching sell delivery method`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { sellDeliveryMethod = "WIRE" }
    val result =
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        "WIRE",
        null
      )
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
  fun `test calculateFromQuote accepts a matching buy delivery method`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply { buyDeliveryMethod = "WIRE" }
    val result =
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        null,
        "WIRE"
      )
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
  fun `test calculateFromQuote accepts a non-null funding method when the quote has no delivery method`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns usdcQuote()
    val result =
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        "SWIFT",
        "bank_account",
      )
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
  fun `test calculateFromQuote accepts a null funding method when the quote has a delivery method`() {
    val quoteId = "id"
    every { sep38QuoteStore.findByQuoteId(quoteId) } returns
      usdcQuote().apply {
        sellDeliveryMethod = "WIRE"
        buyDeliveryMethod = "WIRE"
      }
    val result =
      calculator.calculateFromQuote(
        quoteId,
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
        null,
        null
      )
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

  // --- quote ownership: the caller must be the quote's creator (SEP38OW) ---

  private val ownerToken = createWebAuthJwt(TEST_ACCOUNT, TestConstants.TEST_MEMO)

  /** A quote created by [ownerToken]'s identity. */
  private fun ownedQuote() = usdcQuote()

  private fun calculateAs(caller: WebAuthJwt?, amount: String = "100") =
    calculator.calculateFromQuote(
      "id",
      caller,
      assetService.getAsset("USDC"),
      null,
      amount,
      null,
      null,
    )

  @Test
  fun `test calculateFromQuote with the caller accepts the creator`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    assertEquals(
      Amounts.builder()
        .amountIn("100")
        .amountInAsset(TEST_ASSET_SEP38_FORMAT)
        .amountOut("98")
        .amountOutAsset("iso4217:USD")
        .feeDetails(FeeDetails("2", "iso4217:USD"))
        .build(),
      calculateAs(ownerToken),
    )
  }

  @Test
  fun `test validateQuoteAgainstRequestInfo with the caller accepts the creator`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    val result =
      calculator.validateQuoteAgainstRequestInfo(
        "id",
        ownerToken,
        assetService.getAsset("USDC"),
        null,
        "100",
      )
    assertEquals(TEST_ASSET_SEP38_FORMAT, result.sellAsset)
  }

  @Test
  fun `test calculateFromQuote with the caller accepts a muxed creator`() {
    val muxed = createMuxedWebAuthJwt(TEST_ACCOUNT, 7)
    every { sep38QuoteStore.findByQuoteId("id") } returns
      ownedQuote().apply {
        creatorAccountId = muxed.muxedAccount
        creatorMemo = null
        creatorMemoType = null
      }
    assertEquals("100", calculateAs(muxed).amountIn)
  }

  @Test
  fun `test calculateFromQuote with the caller rejects another account as Quote not found`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    val other = createWebAuthJwt(TestHelper.TEST_ACCOUNT, TestConstants.TEST_MEMO)
    val ex = assertThrows<BadRequestException> { calculateAs(other) }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test validateQuoteAgainstRequestInfo with the caller rejects another account as Quote not found`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    val other = createWebAuthJwt(TestHelper.TEST_ACCOUNT, TestConstants.TEST_MEMO)
    val ex =
      assertThrows<BadRequestException> {
        calculator.validateQuoteAgainstRequestInfo(
          "id",
          other,
          assetService.getAsset("USDC"),
          null,
          "100",
        )
      }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test calculateFromQuote with the caller rejects the same account under another memo`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    val otherMemo = createWebAuthJwt(TEST_ACCOUNT, "999")
    val ex = assertThrows<BadRequestException> { calculateAs(otherMemo) }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test calculateFromQuote with a null caller is Quote not found`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    val ex = assertThrows<BadRequestException> { calculateAs(null) }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test a non-owner gets Quote not found for a used quote`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns
      ownedQuote().apply { transactionId = "existing-txn-id" }
    val other = createWebAuthJwt(TestHelper.TEST_ACCOUNT, TestConstants.TEST_MEMO)
    val ex = assertThrows<BadRequestException> { calculateAs(other) }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test a non-owner gets Quote not found for an expired quote`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns
      ownedQuote().apply { expiresAt = Instant.parse("2000-01-01T00:00:00Z") }
    val other = createWebAuthJwt(TestHelper.TEST_ACCOUNT, TestConstants.TEST_MEMO)
    val ex = assertThrows<BadRequestException> { calculateAs(other) }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test a non-owner gets Quote not found for a mismatched amount`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns ownedQuote()
    val other = createWebAuthJwt(TestHelper.TEST_ACCOUNT, TestConstants.TEST_MEMO)
    val ex = assertThrows<BadRequestException> { calculateAs(other, amount = "99") }
    assertEquals("Quote not found", ex.message)
  }

  @Test
  fun `test the owner of a used quote still gets the already used message`() {
    every { sep38QuoteStore.findByQuoteId("id") } returns
      ownedQuote().apply { transactionId = "existing-txn-id" }
    val ex = assertThrows<BadRequestException> { calculateAs(ownerToken) }
    assertEquals("quote(id=id) has already been used", ex.message)
  }

  // --- expiry (SEP38EX): an expired quote is rejected with its exact message; the instant of
  // expiry itself counts as expired ---

  private val fixedNow = Instant.parse("2030-01-01T00:00:00Z")

  private fun calculatorAtFixedNow() =
    ExchangeAmountsCalculator(sep38QuoteStore, Clock.fixed(fixedNow, ZoneOffset.UTC))

  private fun calculateOwnedQuoteExpiringAt(expiresAt: Instant) =
    calculatorAtFixedNow()
      .also {
        every { sep38QuoteStore.findByQuoteId("id") } returns
          ownedQuote().apply { this.expiresAt = expiresAt }
      }
      .calculateFromQuote("id", ownerToken, assetService.getAsset("USDC"), null, "100", null, null)

  @Test
  fun `test an expired quote of the caller is rejected with the expiry message`() {
    val ex =
      assertThrows<BadRequestException> {
        calculateOwnedQuoteExpiringAt(Instant.parse("2000-01-01T00:00:00Z"))
      }
    assertEquals("quote(id=id) has expired at 2000-01-01T00:00:00Z", ex.message)
  }

  @Test
  fun `test a quote whose expires_at equals the clock instant is expired`() {
    val ex = assertThrows<BadRequestException> { calculateOwnedQuoteExpiringAt(fixedNow) }
    assertEquals("quote(id=id) has expired at 2030-01-01T00:00:00Z", ex.message)
  }

  @Test
  fun `test a quote expiring one second after the clock instant is accepted`() {
    assertEquals("100", calculateOwnedQuoteExpiringAt(fixedNow.plusSeconds(1)).amountIn)
  }
}
