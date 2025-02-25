package org.stellar.anchor.sep6

import com.google.gson.Gson
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.MoreInfoUrlConstructor
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET_SEP38_FORMAT
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.TestConstants.Companion.TEST_QUOTE_ID
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.sep.sep6.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.anchor.api.shared.RefundPayment
import org.stellar.anchor.api.shared.Refunds
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.Sep6Config
import org.stellar.anchor.event.EventService
import org.stellar.anchor.util.ExchangeAmountsCalculator
import org.stellar.anchor.util.ExchangeAmountsCalculator.Amounts
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.SepRequestValidator

class Sep6ServiceTest {
  companion object {
    val gson: Gson = GsonUtils.getInstance()
    val token = TestHelper.createWebAuthJwt(TEST_ACCOUNT, TEST_MEMO)
  }

  private val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")

  @MockK(relaxed = true) lateinit var appConfig: AppConfig
  @MockK(relaxed = true) lateinit var sep6Config: Sep6Config
  @MockK(relaxed = true) lateinit var requestValidator: SepRequestValidator
  @MockK(relaxed = true) lateinit var clientFinder: ClientFinder
  @MockK(relaxed = true) lateinit var txnStore: Sep6TransactionStore
  @MockK(relaxed = true) lateinit var exchangeAmountsCalculator: ExchangeAmountsCalculator
  @MockK(relaxed = true) lateinit var eventService: EventService
  @MockK(relaxed = true) lateinit var eventSession: EventService.Session
  @MockK(relaxed = true) lateinit var sep6MoreInfoUrlConstructor: MoreInfoUrlConstructor

  private lateinit var sep6Service: Sep6Service

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { sep6Config.features.isAccountCreation } returns false
    every { sep6Config.features.isClaimableBalances } returns false
    every { clientFinder.getClientName(token) } returns "vibrant"
    every { txnStore.newInstance() } returns PojoSep6Transaction()
    every { eventService.createSession(any(), any()) } returns eventSession
    every { requestValidator.getDepositAsset(TEST_ASSET) } returns asset
    every { requestValidator.getWithdrawAsset(TEST_ASSET) } returns asset
    every { sep6MoreInfoUrlConstructor.construct(any(), any()) } returns
      "https://example.com/more_info"
    sep6Service =
      Sep6Service(
        appConfig,
        sep6Config,
        assetService,
        requestValidator,
        clientFinder,
        txnStore,
        exchangeAmountsCalculator,
        eventService,
        sep6MoreInfoUrlConstructor,
      )
  }

  private val asset = assetService.getAsset(TEST_ASSET) as StellarAssetInfo

  @Test
  fun `test info response`() {
    val infoResponse = sep6Service.info
    assertEquals(
      gson.fromJson(Sep6ServiceTestData.infoJson, InfoResponse::class.java),
      infoResponse,
    )
  }

  @Test
  fun `test deposit`() {
    val deadline = 100L
    every { sep6Config.initialUserDeadlineSeconds }.returns(deadline)
    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartDepositRequest.builder()
        .assetCode(TEST_ASSET)
        .account(TEST_ACCOUNT)
        .fundingMethod("bank_account")
        .amount("100")
        .build()
    val response = sep6Service.deposit(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositTxnJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)
    val dbDeadline = slotTxn.captured.userActionRequiredBy.epochSecond
    val expectedDeadline = Instant.now().plusSeconds(deadline).epochSecond
    Assertions.assertTrue(
      dbDeadline >= expectedDeadline - 2,
      "Expected $expectedDeadline got $dbDeadline}",
    )
    Assertions.assertTrue(
      dbDeadline <= expectedDeadline,
      "Expected $expectedDeadline got $dbDeadline}",
    )

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositTxnEventJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test deposit without amount or type`() {
    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request = StartDepositRequest.builder().assetCode(TEST_ASSET).account(TEST_ACCOUNT).build()
    val response = sep6Service.deposit(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositTxnJsonWithoutAmountOrType,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositTxnEventWithoutAmountOrTypeJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test deposit with unsupported asset`() {
    val unsupportedAsset = "??"
    val request =
      StartDepositRequest.builder()
        .assetCode(unsupportedAsset)
        .account(TEST_ACCOUNT)
        .fundingMethod("bank_account")
        .amount("100")
        .build()
    every { requestValidator.getDepositAsset(unsupportedAsset) } throws
      SepValidationException("unsupported asset")

    assertThrows<SepValidationException> { sep6Service.deposit(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(unsupportedAsset) }

    // Verify effects
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit with unsupported type`() {
    val unsupportedType = "??"
    val request =
      StartDepositRequest.builder()
        .assetCode(TEST_ASSET)
        .account(TEST_ACCOUNT)
        .fundingMethod(unsupportedType)
        .amount("100")
        .build()
    every { requestValidator.validateTypes(unsupportedType, TEST_ASSET, any()) } throws
      SepValidationException("unsupported type")

    assertThrows<SepValidationException> { sep6Service.deposit(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes(unsupportedType, TEST_ASSET, asset.sep6.deposit.methods)
    }

    // Verify effects
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit with bad amount`() {
    val badAmount = "0"
    val request =
      StartDepositRequest.builder()
        .assetCode(TEST_ASSET)
        .account(TEST_ACCOUNT)
        .fundingMethod("bank_account")
        .amount(badAmount)
        .build()
    every { requestValidator.validateAmount(badAmount, TEST_ASSET, any(), any(), any()) } throws
      SepValidationException("bad amount")

    assertThrows<SepValidationException> { sep6Service.deposit(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", TEST_ASSET, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        badAmount,
        TEST_ASSET,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }

    // Verify effects
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit does not send event if transaction fails to save`() {
    every { txnStore.save(any()) } throws RuntimeException("unexpected failure")

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartDepositRequest.builder()
        .assetCode(TEST_ASSET)
        .account(TEST_ACCOUNT)
        .fundingMethod("bank_account")
        .amount("100")
        .build()

    assertThrows<java.lang.RuntimeException> { sep6Service.deposit(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify { eventSession wasNot called }
  }

  @Test
  fun `test deposit-exchange with quote`() {
    val deadline = 100L
    every { sep6Config.initialUserDeadlineSeconds }.returns(deadline)
    val sourceAsset = "iso4217:USD"
    val destinationAsset = TEST_ASSET
    val amount = "100"

    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    every { exchangeAmountsCalculator.calculateFromQuote(TEST_QUOTE_ID, any(), any()) } returns
      Amounts.builder()
        .amountIn("100")
        .amountInAsset(sourceAsset)
        .amountOut("98")
        .amountOutAsset(TEST_ASSET_SEP38_FORMAT)
        .feeDetails(FeeDetails("2", TEST_ASSET_SEP38_FORMAT))
        .build()

    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(destinationAsset)
        .sourceAsset(sourceAsset)
        .quoteId(TEST_QUOTE_ID)
        .amount(amount)
        .account(TEST_ACCOUNT)
        .fundingMethod("SWIFT")
        .build()
    val response = sep6Service.depositExchange(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("SWIFT", asset.code, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) {
      exchangeAmountsCalculator.calculateFromQuote(TEST_QUOTE_ID, any(), "100")
    }
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositExchangeTxnJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)
    val dbDeadline = slotTxn.captured.userActionRequiredBy.epochSecond
    val expectedDeadline = Instant.now().plusSeconds(deadline).epochSecond
    Assertions.assertTrue(
      dbDeadline >= expectedDeadline - 2,
      "Expected $expectedDeadline got $dbDeadline}",
    )
    Assertions.assertTrue(
      dbDeadline <= expectedDeadline,
      "Expected $expectedDeadline got $dbDeadline}",
    )

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositExchangeTxnEventJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test deposit-exchange without quote`() {
    val sourceAsset = "iso4217:USD"
    val destinationAsset = TEST_ASSET
    val amount = "100"

    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(destinationAsset)
        .sourceAsset(sourceAsset)
        .amount(amount)
        .account(TEST_ACCOUNT)
        .fundingMethod("SWIFT")
        .build()
    val response = sep6Service.depositExchange(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("SWIFT", asset.code, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositExchangeTxnWithoutQuoteJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)

    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositExchangeTxnEventWithoutQuoteJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.depositResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test deposit-exchange with unsupported destination asset`() {
    val unsupportedAsset = "??"
    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(unsupportedAsset)
        .sourceAsset("iso4217:USD")
        .amount("100")
        .account(TEST_ACCOUNT)
        .fundingMethod("SWIFT")
        .build()
    every { requestValidator.getDepositAsset(unsupportedAsset) } throws
      SepValidationException("unsupported asset")

    assertThrows<SepValidationException> { sep6Service.depositExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(unsupportedAsset) }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit-exchange with unsupported source asset`() {
    val unsupportedAsset = "??"
    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(TEST_ASSET)
        .sourceAsset(unsupportedAsset)
        .amount("100")
        .account(TEST_ACCOUNT)
        .fundingMethod("SWIFT")
        .build()

    assertThrows<SepValidationException> { sep6Service.depositExchange(token, request) }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit-exchange with unsupported type`() {
    val unsupportedType = "??"
    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(TEST_ASSET)
        .sourceAsset("iso4217:USD")
        .amount("100")
        .account(TEST_ACCOUNT)
        .fundingMethod(unsupportedType)
        .build()
    every { requestValidator.validateTypes(unsupportedType, TEST_ASSET, any()) } throws
      SepValidationException("unsupported type")

    assertThrows<SepValidationException> { sep6Service.depositExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes(unsupportedType, TEST_ASSET, asset.sep6.deposit.methods)
    }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit-exchange with bad amount`() {
    val sourceAsset = "iso4217:USD"
    val destinationAsset = TEST_ASSET
    val badAmount = "100"

    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(destinationAsset)
        .sourceAsset(sourceAsset)
        .amount(badAmount)
        .account(TEST_ACCOUNT)
        .fundingMethod("SWIFT")
        .build()
    every { requestValidator.validateAmount(badAmount, TEST_ASSET, any(), any(), any()) } throws
      SepValidationException("bad amount")

    assertThrows<SepValidationException> { sep6Service.depositExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("SWIFT", TEST_ASSET, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        badAmount,
        TEST_ASSET,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test deposit-exchange does not send event if transaction fails`() {
    every { txnStore.save(any()) } throws RuntimeException("unexpected failure")

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartDepositExchangeRequest.builder()
        .destinationAsset(TEST_ASSET)
        .sourceAsset("iso4217:USD")
        .amount("100")
        .account(TEST_ACCOUNT)
        .fundingMethod("SWIFT")
        .build()
    assertThrows<java.lang.RuntimeException> { sep6Service.depositExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getDepositAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("SWIFT", asset.code, asset.sep6.deposit.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.deposit.minAmount,
        asset.sep6.deposit.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify { eventSession wasNot called }
  }

  @Test
  fun `test withdraw`() {
    val deadline = 100L
    every { sep6Config.initialUserDeadlineSeconds }.returns(deadline)
    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawRequest.builder()
        .assetCode(TEST_ASSET)
        .fundingMethod("bank_account")
        .amount("100")
        .refundMemo("some text")
        .refundMemoType("text")
        .build()

    val response = sep6Service.withdraw(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawTxnJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)
    val dbDeadline = slotTxn.captured.userActionRequiredBy.epochSecond
    val expectedDeadline = Instant.now().plusSeconds(deadline).epochSecond
    Assertions.assertTrue(
      dbDeadline >= expectedDeadline - 2,
      "Expected $expectedDeadline got $dbDeadline}",
    )
    Assertions.assertTrue(
      dbDeadline <= expectedDeadline,
      "Expected $expectedDeadline got $dbDeadline}",
    )

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawTxnEventJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    assertEquals(slotTxn.captured.memo, response.memo)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test withdraw from requested account`() {
    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawRequest.builder()
        .assetCode(TEST_ASSET)
        .account("requested_account")
        .refundMemo("some text")
        .refundMemoType("text")
        .build()
    sep6Service.withdraw(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) { requestValidator.validateAccount("requested_account") }

    // Verify effects
    assertEquals("requested_account", slotTxn.captured.fromAccount)
    assertEquals("requested_account", slotEvent.captured.transaction.sourceAccount)
  }

  @Test
  fun `test withdraw without amount or type`() {
    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawRequest.builder()
        .assetCode(TEST_ASSET)
        .refundMemo("some text")
        .refundMemoType("text")
        .build()
    val response = sep6Service.withdraw(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawTxnWithoutAmountOrTypeJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawTxnEventWithoutAmountOrTypeJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    assertEquals(slotTxn.captured.memo, response.memo)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test withdraw with unsupported asset`() {
    val unsupportedAsset = "??"
    val request =
      StartWithdrawRequest.builder()
        .assetCode(unsupportedAsset)
        .fundingMethod("bank_account")
        .amount("100")
        .build()
    every { requestValidator.getWithdrawAsset(unsupportedAsset) } throws
      SepValidationException("unsupported asset")

    assertThrows<SepValidationException> { sep6Service.withdraw(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(unsupportedAsset) }

    // Verify effects
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw with unsupported type`() {
    val unsupportedType = "??"
    val request =
      StartWithdrawRequest.builder()
        .assetCode(TEST_ASSET)
        .fundingMethod(unsupportedType)
        .amount("100")
        .build()
    every { requestValidator.getWithdrawAsset(TEST_ASSET) } returns
      assetService.getAsset(TEST_ASSET) as StellarAssetInfo
    every { requestValidator.validateTypes(unsupportedType, TEST_ASSET, any()) } throws
      SepValidationException("unsupported type")

    assertThrows<SepValidationException> { sep6Service.withdraw(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes(unsupportedType, asset.code, asset.sep6.withdraw.methods)
    }

    // Verify effects
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw with bad amount`() {
    val badAmount = "0"
    val request =
      StartWithdrawRequest.builder()
        .assetCode(TEST_ASSET)
        .fundingMethod("bank_account")
        .amount(badAmount)
        .build()
    every { requestValidator.validateAmount(badAmount, TEST_ASSET, any(), any(), any()) } throws
      SepValidationException("bad amount")

    assertThrows<SepValidationException> { sep6Service.withdraw(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        badAmount,
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }

    // Verify effects
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw does not send event if transaction fails to save`() {
    every { txnStore.save(any()) } throws RuntimeException("unexpected failure")

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawRequest.builder()
        .assetCode(TEST_ASSET)
        .fundingMethod("bank_account")
        .amount("100")
        .build()

    assertThrows<java.lang.RuntimeException> { sep6Service.withdraw(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify { eventSession wasNot called }
  }

  @Test
  fun `test withdraw-exchange with quote`() {
    val sourceAsset = TEST_ASSET
    val destinationAsset = "iso4217:USD"

    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    every { exchangeAmountsCalculator.calculateFromQuote(TEST_QUOTE_ID, any(), any()) } returns
      Amounts.builder()
        .amountIn("100")
        .amountInAsset(TEST_ASSET_SEP38_FORMAT)
        .amountOut("98")
        .amountOutAsset(destinationAsset)
        .feeDetails(FeeDetails("2", destinationAsset))
        .build()

    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(sourceAsset)
        .destinationAsset(destinationAsset)
        .quoteId(TEST_QUOTE_ID)
        .fundingMethod("bank_account")
        .amount("100")
        .refundMemo("some text")
        .refundMemoType("text")
        .build()
    val response = sep6Service.withdrawExchange(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) {
      exchangeAmountsCalculator.calculateFromQuote(TEST_QUOTE_ID, any(), "100")
    }
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawExchangeTxnJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawExchangeTxnEventJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    assertEquals(slotTxn.captured.memo, response.memo)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test withdraw-exchange without quote`() {
    val deadline = 100L
    every { sep6Config.initialUserDeadlineSeconds }.returns(deadline)
    val sourceAsset = TEST_ASSET
    val destinationAsset = "iso4217:USD"

    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(sourceAsset)
        .destinationAsset(destinationAsset)
        .fundingMethod("bank_account")
        .amount("100")
        .refundMemo("some text")
        .refundMemoType("text")
        .build()
    val response = sep6Service.withdrawExchange(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawExchangeTxnWithoutQuoteJson,
      gson.toJson(slotTxn.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotTxn.captured.id.isNotEmpty())
    assertNotNull(slotTxn.captured.startedAt)
    val dbDeadline = slotTxn.captured.userActionRequiredBy.epochSecond
    val expectedDeadline = Instant.now().plusSeconds(deadline).epochSecond
    Assertions.assertTrue(
      dbDeadline >= expectedDeadline - 2,
      "Expected $expectedDeadline got $dbDeadline}",
    )
    Assertions.assertTrue(
      dbDeadline <= expectedDeadline,
      "Expected $expectedDeadline got $dbDeadline}",
    )

    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawExchangeTxnWithoutQuoteEventJson,
      gson.toJson(slotEvent.captured),
      JSONCompareMode.LENIENT,
    )
    assert(slotEvent.captured.id.isNotEmpty())
    assert(slotEvent.captured.transaction.id.isNotEmpty())
    assertNotNull(slotEvent.captured.transaction.startedAt)

    // Verify response
    assertEquals(slotTxn.captured.id, response.id)
    assertEquals(slotTxn.captured.memo, response.memo)
    JSONAssert.assertEquals(
      Sep6ServiceTestData.withdrawResJson,
      gson.toJson(response),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test withdraw-exchange from requested account`() {
    val sourceAsset = TEST_ASSET
    val destinationAsset = "iso4217:USD"

    val slotTxn = slot<Sep6Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(sourceAsset)
        .destinationAsset(destinationAsset)
        .fundingMethod("bank_account")
        .amount("100")
        .account("requested_account")
        .refundMemo("some text")
        .refundMemoType("text")
        .build()
    sep6Service.withdrawExchange(token, request)

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) { requestValidator.validateAccount("requested_account") }

    // Verify effects
    assertEquals("requested_account", slotTxn.captured.fromAccount)
    assertEquals("requested_account", slotEvent.captured.transaction.sourceAccount)
  }

  @Test
  fun `test withdraw-exchange with unsupported source asset`() {
    val unsupportedAsset = "??"
    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(unsupportedAsset)
        .destinationAsset("iso4217:USD")
        .fundingMethod("bank_account")
        .amount("100")
        .build()
    every { requestValidator.getWithdrawAsset(unsupportedAsset) } throws
      SepValidationException("unsupported asset")

    assertThrows<SepValidationException> { sep6Service.withdrawExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(unsupportedAsset) }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw-exchange with unsupported destination asset`() {
    val unsupportedAsset = "??"
    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(TEST_ASSET)
        .destinationAsset(unsupportedAsset)
        .fundingMethod("bank_account")
        .amount("100")
        .build()

    assertThrows<SepValidationException> { sep6Service.withdrawExchange(token, request) }
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw-exchange with unsupported type`() {
    val unsupportedType = "??"
    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(TEST_ASSET)
        .destinationAsset("iso4217:USD")
        .fundingMethod(unsupportedType)
        .amount("100")
        .build()
    every { requestValidator.validateTypes(unsupportedType, TEST_ASSET, any()) } throws
      SepValidationException("unsupported type")

    assertThrows<SepValidationException> { sep6Service.withdrawExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes(unsupportedType, asset.code, asset.sep6.withdraw.methods)
    }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw-exchange with bad amount`() {
    val badAmount = "??"
    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(TEST_ASSET)
        .destinationAsset("iso4217:USD")
        .fundingMethod("bank_account")
        .amount(badAmount)
        .build()
    every { requestValidator.getWithdrawAsset(TEST_ASSET) } returns
      assetService.getAsset(TEST_ASSET) as StellarAssetInfo
    every { requestValidator.validateAmount(badAmount, TEST_ASSET, any(), any(), any()) } throws
      SepValidationException("bad amount")

    assertThrows<SepValidationException> { sep6Service.withdrawExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        badAmount,
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }

    // Verify effects
    verify { exchangeAmountsCalculator wasNot Called }
    verify { txnStore wasNot Called }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `test withdraw-exchange does not send event if transaction fails to save`() {
    every { txnStore.save(any()) } throws RuntimeException("unexpected failure")

    val slotEvent = slot<AnchorEvent>()
    every { eventSession.publish(capture(slotEvent)) } returns Unit

    val request =
      StartWithdrawExchangeRequest.builder()
        .sourceAsset(TEST_ASSET)
        .destinationAsset("iso4217:USD")
        .fundingMethod("bank_account")
        .amount("100")
        .build()
    every { requestValidator.getWithdrawAsset(TEST_ASSET) } returns
      assetService.getAsset(TEST_ASSET) as StellarAssetInfo

    assertThrows<java.lang.RuntimeException> { sep6Service.withdrawExchange(token, request) }

    // Verify validations
    verify(exactly = 1) { requestValidator.getWithdrawAsset(TEST_ASSET) }
    verify(exactly = 1) {
      requestValidator.validateTypes("bank_account", asset.code, asset.sep6.withdraw.methods)
    }
    verify(exactly = 1) {
      requestValidator.validateAmount(
        "100",
        asset.code,
        asset.significantDecimals,
        asset.sep6.withdraw.minAmount,
        asset.sep6.withdraw.maxAmount,
      )
    }
    verify(exactly = 1) { requestValidator.validateAccount(TEST_ACCOUNT) }

    // Verify effects
    verify(exactly = 1) { txnStore.save(any()) }
    verify { eventSession wasNot called }
  }

  @Test
  fun `test find transaction by id`() {
    val depositTxn = createDepositTxn(TEST_ACCOUNT)
    val request = GetTransactionRequest.builder().id(depositTxn.id).lang("en-US").build()
    every { txnStore.findByTransactionId(depositTxn.id) } returns depositTxn

    sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)

    verify { txnStore.findByTransactionId(depositTxn.id) }
  }

  @Test
  fun `test find transaction by stellar transaction id`() {
    val depositTxn = createDepositTxn(TEST_ACCOUNT)
    val request =
      GetTransactionRequest.builder()
        .stellarTransactionId(depositTxn.stellarTransactionId)
        .lang("en-US")
        .build()
    every { txnStore.findByStellarTransactionId(depositTxn.stellarTransactionId) } returns
      depositTxn

    sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)

    verify { txnStore.findByStellarTransactionId(depositTxn.stellarTransactionId) }
  }

  @Test
  fun `test find transaction by external transaction id`() {
    val depositTxn = createDepositTxn(TEST_ACCOUNT)
    val request =
      GetTransactionRequest.builder()
        .externalTransactionId(depositTxn.externalTransactionId)
        .lang("en-US")
        .build()
    every { txnStore.findByExternalTransactionId(depositTxn.externalTransactionId) } returns
      depositTxn

    sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)

    verify { txnStore.findByExternalTransactionId(depositTxn.externalTransactionId) }
  }

  @Test
  fun `test find transaction missing ids`() {
    val request = GetTransactionRequest.builder().lang("en-US").build()

    assertThrows<SepValidationException> {
      sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)
    }
    verify { txnStore wasNot Called }
  }

  @Test
  fun `test find transaction with non-existent id`() {
    val request =
      GetTransactionRequest.builder().id(UUID.randomUUID().toString()).lang("en-US").build()
    every { txnStore.findByTransactionId(any()) } returns null

    assertThrows<NotFoundException> {
      sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)
    }

    verify { txnStore.findByTransactionId(any()) }
  }

  @Test
  fun `test find transaction belonging to different account`() {
    val depositTxn = createDepositTxn("other-account")
    val request = GetTransactionRequest.builder().id(depositTxn.id).lang("en-US").build()
    every { txnStore.findByTransactionId(depositTxn.id) } returns depositTxn

    assertThrows<NotFoundException> {
      sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)
    }

    verify { txnStore.findByTransactionId(depositTxn.id) }
  }

  @Test
  fun `test find transaction with different account memo`() {
    val depositTxn = createDepositTxn(TEST_ACCOUNT, "other-memo")
    val request = GetTransactionRequest.builder().id(depositTxn.id).lang("en-US").build()
    every { txnStore.findByTransactionId(depositTxn.id) } returns depositTxn

    assertThrows<NotFoundException> {
      sep6Service.findTransaction(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)
    }

    verify { txnStore.findByTransactionId(depositTxn.id) }
  }

  @Test
  fun `test find transactions with account mismatch`() {
    val request =
      GetTransactionsRequest.builder()
        .assetCode(TEST_ASSET)
        .account(TEST_ACCOUNT)
        .noOlderThan(Instant.now().toString())
        .limit(10)
        .kind("deposit")
        .pagingId("1")
        .lang("en-US")
        .build()

    assertThrows<SepNotAuthorizedException> {
      sep6Service.findTransactions(TestHelper.createWebAuthJwt("other-account"), request)
    }
    verify { txnStore wasNot Called }
  }

  @Test
  fun `test find transactions with unsupported asset`() {
    val request =
      GetTransactionsRequest.builder()
        .assetCode("???")
        .account(TEST_ACCOUNT)
        .noOlderThan(Instant.now().toString())
        .limit(10)
        .kind("deposit")
        .pagingId("1")
        .lang("en-US")
        .build()

    assertThrows<SepValidationException> {
      sep6Service.findTransactions(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)
    }
    verify { txnStore wasNot Called }
  }

  @Test
  fun `test find transactions`() {
    val depositTxn = createDepositTxn(TEST_ACCOUNT)
    every { txnStore.findTransactions(TEST_ACCOUNT, any(), any()) } returns listOf(depositTxn)
    val request =
      GetTransactionsRequest.builder()
        .assetCode(TEST_ASSET)
        .account(TEST_ACCOUNT)
        .noOlderThan(Instant.now().toString())
        .limit(10)
        .kind("deposit")
        .pagingId("1")
        .lang("en-US")
        .build()
    val response = sep6Service.findTransactions(TestHelper.createWebAuthJwt(TEST_ACCOUNT), request)

    verify(exactly = 1) { txnStore.findTransactions(TEST_ACCOUNT, null, request) }

    JSONAssert.assertEquals(Sep6ServiceTestData.transactionsJson, gson.toJson(response), true)
  }

  private fun createDepositTxn(
    sep10Account: String,
    sep10AccountMemo: String? = null,
  ): Sep6Transaction {
    val txn = PojoSep6Transaction()

    val payment = RefundPayment()
    payment.id = "refund-payment-id"
    payment.idType = RefundPayment.IdType.EXTERNAL
    payment.amount = Amount.create("100", "USD")
    payment.fee = Amount.create("0", "USD")

    val refunds = Refunds()
    refunds.amountRefunded = Amount.create("100", "USD")
    refunds.amountFee = Amount.create("0", "USD")
    refunds.payments = arrayOf(payment)

    txn.id = "2cb630d3-030b-4a0e-9d9d-f26b1df25d12"
    txn.kind = "deposit"
    txn.status = "complete"
    txn.statusEta = 5
    txn.amountIn = "100"
    txn.amountInAsset = "USD"
    txn.amountOut = "98"
    txn.amountOutAsset = "USDC"
    txn.amountFee = "2"
    txn.amountOutAsset = "stellar:USDC:GABCD"
    txn.sep10Account = sep10Account
    txn.sep10AccountMemo = sep10AccountMemo
    txn.fromAccount = "GABCD"
    txn.toAccount = "GABCD"
    txn.memo = "some memo"
    txn.memoType = "text"
    txn.startedAt = Instant.ofEpochMilli(1690908800000L)
    txn.updatedAt = Instant.ofEpochMilli(1690908800000L)
    txn.completedAt = Instant.ofEpochMilli(1690908800000L)
    txn.stellarTransactionId = "stellar-id"
    txn.externalTransactionId = "external-id"
    txn.message = "some message"
    txn.refunds = refunds
    txn.requiredInfoMessage = "some info message"
    txn.requiredInfoUpdates = listOf("first_name", "last_name")

    return txn
  }
}
