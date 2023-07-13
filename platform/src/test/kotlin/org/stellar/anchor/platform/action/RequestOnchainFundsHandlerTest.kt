package org.stellar.anchor.platform.action

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.rpc.action.AmountAssetRequest
import org.stellar.anchor.api.rpc.action.AmountRequest
import org.stellar.anchor.api.rpc.action.RequestOnchainFundsRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.SepDepositInfo
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.CustodyConfig
import org.stellar.anchor.custody.CustodyService
import org.stellar.anchor.horizon.Horizon
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.service.Sep24DepositInfoNoneGenerator
import org.stellar.anchor.platform.service.Sep24DepositInfoSelfGenerator
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24Transaction
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.util.GsonUtils

class RequestOnchainFundsHandlerTest {

  companion object {
    private val GSON = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val FIAT_USD_CODE = "USD"
  }

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var horizon: Horizon

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var custodyConfig: CustodyConfig

  @MockK(relaxed = true) private lateinit var custodyService: CustodyService

  @MockK(relaxed = true)
  private lateinit var sep24DepositInfoGenerator: Sep24DepositInfoNoneGenerator

  private lateinit var handler: RequestOnchainFundsHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    this.handler =
      RequestOnchainFundsHandler(
        txn24Store,
        txn31Store,
        requestValidator,
        horizon,
        assetService,
        custodyService,
        custodyConfig,
        sep24DepositInfoGenerator
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    val spyTxn24 = spyk(txn24)

    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns "38"

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[request_onchain_funds] is not supported for status[incomplete], kind[null] and protocol[38]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedStatus() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_TRUST.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[request_onchain_funds] is not supported for status[pending_trust], kind[withdrawal] and protocol[24]",
      ex.message
    )
  }

  @Test
  fun test_handle_transferReceived() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[request_onchain_funds] is not supported for status[pending_anchor], kind[withdrawal] and protocol[24]",
      ex.message
    )
  }

  @Test
  fun test_handle_unsupportedKind() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Action[request_onchain_funds] is not supported for status[incomplete], kind[deposit] and protocol[24]",
      ex.message
    )
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { requestValidator.validate(request) } throws InvalidParamsException("Invalid request")

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Invalid request", ex.message?.trimIndent())
  }

  @Test
  fun test_handle_ok_withExpectedAmount() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .memo("testMemo")
        .memoType("text")
        .destinationAccount("testDestinationAccount")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns false

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { custodyService.createTransaction(ofType(Sep24Transaction::class)) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = "testMemo"
    expectedSep24Txn.memoType = "text"
    expectedSep24Txn.toAccount = "testDestinationAccount"
    expectedSep24Txn.withdrawAnchorAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedSep24Txn),
      GSON.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = "testMemo"
    expectedResponse.memoType = "text"
    expectedResponse.destinationAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedResponse),
      GSON.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_autogeneratedMemo() {
    val sep24DepositInfoGenerator: Sep24DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn24Store,
        txn31Store,
        requestValidator,
        horizon,
        assetService,
        custodyService,
        custodyConfig,
        sep24DepositInfoGenerator
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val depositInfo = SepDepositInfo("testDestinationAccount2", "testMemo2", "text")

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns false
    every { sep24DepositInfoGenerator.generate(ofType(Sep24Transaction::class)) } returns
      depositInfo

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { custodyService.createTransaction(ofType(Sep24Transaction::class)) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = "testMemo2"
    expectedSep24Txn.memoType = "text"
    expectedSep24Txn.toAccount = "testDestinationAccount2"
    expectedSep24Txn.withdrawAnchorAccount = "testDestinationAccount2"

    JSONAssert.assertEquals(
      GSON.toJson(expectedSep24Txn),
      GSON.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = "testMemo2"
    expectedResponse.memoType = "text"
    expectedResponse.destinationAccount = "testDestinationAccount2"

    JSONAssert.assertEquals(
      GSON.toJson(expectedResponse),
      GSON.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_withExpectedAmount_custodyIntegrationEnabled() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .memo("testMemo")
        .memoType("text")
        .destinationAccount("testDestinationAccount")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val sep24CustodyTxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    every { custodyService.createTransaction(capture(sep24CustodyTxnCapture)) } just Runs

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = "testMemo"
    expectedSep24Txn.memoType = "text"
    expectedSep24Txn.toAccount = "testDestinationAccount"
    expectedSep24Txn.withdrawAnchorAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedSep24Txn),
      GSON.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    JSONAssert.assertEquals(
      GSON.toJson(expectedSep24Txn),
      GSON.toJson(sep24CustodyTxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = "testMemo"
    expectedResponse.memoType = "text"
    expectedResponse.destinationAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedResponse),
      GSON.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_withoutAmountExpected() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
        .memo("testMemo")
        .memoType("text")
        .destinationAccount("testDestinationAccount")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns false

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { custodyService.createTransaction(ofType(Sep24Transaction::class)) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = "testMemo"
    expectedSep24Txn.memoType = "text"
    expectedSep24Txn.toAccount = "testDestinationAccount"
    expectedSep24Txn.withdrawAnchorAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedSep24Txn),
      GSON.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = "testMemo"
    expectedResponse.memoType = "text"
    expectedResponse.destinationAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedResponse),
      GSON.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }

  @Test
  fun test_handle_ok_withoutAmounts_amountsPresent() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo("testMemo")
        .memoType("text")
        .destinationAccount("testDestinationAccount")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountIn = "1"
    txn24.amountInAsset = FIAT_USD
    txn24.amountOut = "0.9"
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.amountFeeAsset = STELLAR_USDC
    txn24.amountExpected = "1"
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns false

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { custodyService.createTransaction(ofType(Sep24Transaction::class)) }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = "testMemo"
    expectedSep24Txn.memoType = "text"
    expectedSep24Txn.toAccount = "testDestinationAccount"
    expectedSep24Txn.withdrawAnchorAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedSep24Txn),
      GSON.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = "testMemo"
    expectedResponse.memoType = "text"
    expectedResponse.destinationAccount = "testDestinationAccount"

    JSONAssert.assertEquals(
      GSON.toJson(expectedResponse),
      GSON.toJson(response),
      JSONCompareMode.STRICT
    )

    assertTrue(expectedSep24Txn.updatedAt >= startDate)
    assertTrue(expectedSep24Txn.updatedAt <= endDate)
  }

  @Test
  fun test_handle_withoutAmounts_amountsAbsent() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_in is required", ex.message)
  }

  @Test
  fun test_handle_notNoneGenerator() {
    val sep24DepositInfoGenerator: Sep24DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn24Store,
        txn31Store,
        requestValidator,
        horizon,
        assetService,
        custodyService,
        custodyConfig,
        sep24DepositInfoGenerator
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo("testMemo")
        .memoType("text")
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .amountFee(AmountAssetRequest("1", FIAT_USD))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      "Anchor is not configured to accept memo, memo_type and destination_account",
      ex.message
    )
  }

  @Test
  fun test_handle_notAllAmounts() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      "All or none of the amount_in, amount_out, and amount_fee should be set",
      ex.message
    )
  }

  @Test
  fun test_handle_ok_invalidMemo() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .amountFee(AmountAssetRequest("1", FIAT_USD))
        .transactionId(TX_ID)
        .memo("testMemo")
        .memoType("invalidMemoType")
        .destinationAccount("testDestinationAccount")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Invalid memo or memo_type: Invalid memo type: invalidMemoType", ex.message)
  }

  @Test
  fun test_handle_ok_missingMemo() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .amountFee(AmountAssetRequest("1", FIAT_USD))
        .transactionId(TX_ID)
        .destinationAccount("testDestinationAccount")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("memo and memo_type are required", ex.message)
  }

  @Test
  fun test_handle_ok_missingDestinationAccount() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .amountFee(AmountAssetRequest("1", FIAT_USD))
        .transactionId(TX_ID)
        .memo("testMemo")
        .memoType("text")
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("destination_account is required", ex.message)
  }

  @Test
  fun test_handle_invalidAmounts() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", STELLAR_USDC))
        .amountFee(AmountAssetRequest("1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    request.amountIn.amount = "-1"
    var ex = assertThrows<BadRequestException> { handler.handle(request) }
    assertEquals("amount_in.amount should be positive", ex.message)
    request.amountIn.amount = "1"

    request.amountOut.amount = "-1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_out.amount should be positive", ex.message)
    request.amountOut.amount = "1"

    request.amountFee.amount = "-1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_fee.amount should be non-negative", ex.message)
    request.amountFee.amount = "1"

    request.amountExpected.amount = "-1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_expected.amount should be positive", ex.message)
  }
}
