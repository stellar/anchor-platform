package org.stellar.anchor.platform.service

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.util.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest
import org.stellar.anchor.api.custody.CreateTransactionPaymentResponse
import org.stellar.anchor.api.exception.CustodyException
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.api.exception.custody.CustodyBadRequestException
import org.stellar.anchor.api.exception.custody.CustodyNotFoundException
import org.stellar.anchor.api.exception.custody.CustodyServiceUnavailableException
import org.stellar.anchor.api.exception.custody.CustodyTooManyRequestsException
import org.stellar.anchor.custody.CustodyService
import org.stellar.anchor.platform.apiclient.CustodyApiClient
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.util.GsonUtils

class CustodyServiceTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private val TXN_ID = "1"
    private val REQUEST_BODY = "{}"
  }

  @MockK(relaxed = true) private lateinit var custodyApiClient: CustodyApiClient
  private lateinit var custodyService: CustodyService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    custodyService = CustodyServiceImpl(Optional.of(custodyApiClient))
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_createTransaction_sep6Deposit(kind: String) {
    val txn =
      gson.fromJson(sep6DepositEntity, JdbcSep6Transaction::class.java).apply { this.kind = kind }
    val requestCapture = slot<CreateCustodyTransactionRequest>()

    every { custodyApiClient.createTransaction(capture(requestCapture)) } just Runs

    custodyService.createTransaction(txn)

    JSONAssert.assertEquals(
      sep6DepositRequest.replace("testKind", kind),
      gson.toJson(requestCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_createTransaction_sep6Withdrawal(kind: String) {
    val txn =
      gson.fromJson(sep6WithdrawalEntity, JdbcSep6Transaction::class.java).apply {
        this.kind = kind
      }
    val requestCapture = slot<CreateCustodyTransactionRequest>()

    every { custodyApiClient.createTransaction(capture(requestCapture)) } just Runs

    custodyService.createTransaction(txn)

    JSONAssert.assertEquals(
      sep6WithdrawalRequest.replace("testKind", kind),
      gson.toJson(requestCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun test_createTransaction_sep24Deposit() {
    val txn = gson.fromJson(sep24DepositEntity, JdbcSep24Transaction::class.java)

    val requestCapture = slot<CreateCustodyTransactionRequest>()

    every { custodyApiClient.createTransaction(capture(requestCapture)) } just Runs

    custodyService.createTransaction(txn)

    JSONAssert.assertEquals(
      sep24DepositRequest,
      gson.toJson(requestCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun test_createTransaction_sep24Withdrawal() {
    val txn = gson.fromJson(sep24WithdrawalEntity, JdbcSep24Transaction::class.java)
    val requestCapture = slot<CreateCustodyTransactionRequest>()

    every { custodyApiClient.createTransaction(capture(requestCapture)) } just Runs

    custodyService.createTransaction(txn)

    JSONAssert.assertEquals(
      sep24WithdrawalRequest,
      gson.toJson(requestCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun test_createTransaction_sep31() {
    val txn = gson.fromJson(sep31Entity, JdbcSep31Transaction::class.java)
    val requestCapture = slot<CreateCustodyTransactionRequest>()

    every { custodyApiClient.createTransaction(capture(requestCapture)) } just Runs

    custodyService.createTransaction(txn)

    JSONAssert.assertEquals(
      sep31Request,
      gson.toJson(requestCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun test_createTransactionPayment_custody_integration_not_enabled() {
    custodyService = CustodyServiceImpl(Optional.empty())

    val ex =
      assertThrows<InvalidConfigException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Integration with custody service is not enabled", ex.message)
    verify(exactly = 0) { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) }
  }

  @Test
  fun test_createTransactionPayment_custody_integration_enabled() {
    every { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) } returns
      CreateTransactionPaymentResponse(TXN_ID)

    custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)

    verify(exactly = 1) { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) }
  }

  @Test
  fun test_createTransactionPayment_custody_integration_disabled() {
    custodyService = CustodyServiceImpl(Optional.empty())

    val exception =
      assertThrows<InvalidConfigException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Integration with custody service is not enabled", exception.message)

    verify(exactly = 0) { custodyApiClient.createTransactionPayment(any(), any()) }
  }

  @Test
  fun test_createTransactionPayment_custody_server_unavailable() {
    every { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) } throws
      CustodyException("Custody service is unavailable", 503)

    val exception =
      assertThrows<CustodyServiceUnavailableException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Custody service is unavailable", exception.message)
  }

  @Test
  fun test_createTransactionPayment_bad_request() {
    every { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) } throws
      CustodyException("Bad request", 400)

    val exception =
      assertThrows<CustodyBadRequestException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Bad request", exception.message)
  }

  @Test
  fun test_createTransactionPayment_too_many_requests() {
    every { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) } throws
      CustodyException("Too many requests", 429)

    val exception =
      assertThrows<CustodyTooManyRequestsException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Too many requests", exception.message)
  }

  @Test
  fun test_createTransactionPayment_transaction_not_found() {
    every { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) } throws
      CustodyException("Transaction (id=1) is not found", 404)

    val exception =
      assertThrows<CustodyNotFoundException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Transaction (id=1) is not found", exception.message)
  }

  @Test
  fun test_createTransactionPayment_unexpected_status_code() {
    every { custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY) } throws
      CustodyException("Forbidden", 403)

    val exception =
      assertThrows<CustodyException> {
        custodyService.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Forbidden", exception.rawMessage)
  }

  private val sep6DepositEntity =
    """
      {
        "id" : "testId",
        "stellar_transaction_id": "testStellarTransactionId",
        "external_transaction_id": "testExternalTransactionId",
        "status": "pending_anchor",
        "kind": "deposit",
        "started_at": "2022-04-18T14:00:00.000Z",
        "completed_at": "2022-04-18T14:00:00.000Z",
        "updated_at": "2022-04-18T14:00:00.000Z",
        "transfer_received_at": "2022-04-18T14:00:00.000Z",
        "type": "SWIFT",
        "requestAssetCode": "testRequestAssetCode",
        "requestAssetIssuer": "testRequestAssetIssuer",
        "amount_in": "testAmountIn",
        "amount_in_asset": "testAmountInAsset",
        "amount_out": "testAmountOut",
        "amount_out_asset": "testAmountOutAsset",
        "amount_fee": "testAmountFee",
        "amount_fee_asset": "testAmountFeeAsset", 
        "amount_expected": "testAmountExpected",
        "web_auth_account": "testWebAuthAccount",
        "web_auth_account_memo": "testWebAuthAccountMemo",
        "from_account": "testFromAccount",
        "to_account": "testToAccount",
        "memo": "testMemo",
        "memo_type": "testMemoType",
        "quote_id": "testQuoteId",
        "message": "testMessage",
        "refundMemo": "testRefundMemo",
        "refundMemoType": "testRefundMemoType"
      }
    """
      .trimIndent()

  private val sep6DepositRequest =
    """
      {
        "id": "testId",
        "memo": "testMemo",
        "memoType": "testMemoType",
        "protocol": "6",
        "toAccount": "testToAccount",
        "amount": "testAmountOut",
        "asset": "testAmountOutAsset",
        "kind": "testKind"
      }
    """
      .trimIndent()

  private val sep6WithdrawalEntity =
    """
      {
        "id": "testId",
        "stellar_transaction_id": "testStellarTransactionId",
        "external_transaction_id": "testExternalTransactionId",
        "status": "pending_anchor",
        "kind": "withdrawal",
        "started_at": "2022-04-18T14:00:00.000Z",
        "completed_at": "2022-04-18T14:00:00.000Z",
        "updated_at": "2022-04-18T14:00:00.000Z",
        "transfer_received_at": "2022-04-18T14:00:00.000Z",
        "type": "bank_account",
        "requestAssetCode": "testRequestAssetCode",
        "requestAssetIssuer": "testRequestAssetIssuer",
        "amount_in": "testAmountIn",
        "amount_in_asset": "testAmountInAsset",
        "amount_out": "testAmountOut",
        "amount_out_asset": "testAmountOutAsset",
        "amount_fee": "testAmountFee",
        "amount_fee_asset": "testAmountFeeAsset",
        "amount_expected": "testAmountExpected",
        "web_auth_account": "testWebAuthAccount",
        "web_auth_account_memo": "testWebAuthAccountMemo",
        "withdraw_anchor_account": "testWithdrawAnchorAccount",
        "from_account": "testFromAccount",
        "to_account": "testToAccount",
        "memo": "testMemo",
        "memo_type": "testMemoType",
        "quote_id": "testQuoteId",
        "message": "testMessage",
        "refundMemo": "testRefundMemo",
        "refundMemoType": "testRefundMemoType"
      }
    """
      .trimIndent()

  private val sep6WithdrawalRequest =
    """
      {
        "id": "testId",
        "memo": "testMemo",
        "memoType": "testMemoType",
        "protocol": "6",
        "fromAccount": "testFromAccount",
        "toAccount": "testWithdrawAnchorAccount",
        "amount": "testAmountExpected",
        "asset": "testAmountInAsset",
        "kind": "testKind"
      }
    """
      .trimIndent()

  private val sep24DepositEntity =
    """
{
  "id" : "testId",
  "status": "pending_anchor",
  "updated_at": "2022-04-18T14:00:00.000Z",
  "amount_in": "testAmountIn",
  "amount_in_asset": "testAmountInAsset",
  "amount_out": "testAmountOut",
  "amount_out_asset": "testAmountOutAsset",
  "amount_fee": "testAmountFee",
  "amount_fee_asset": "testAmountFeeAsset",
  "started_at": "2022-04-18T14:00:00.000Z",
  "completed_at": "2022-04-18T14:00:00.000Z",
  "transfer_received_at": "2022-04-18T14:00:00.000Z",
  "stellar_transaction_id": "testStellarTransactionId",
  "external_transaction_id": "testExternalTransactionId",
  "kind": "deposit",
  "status_eta":  "1",
  "kyc_verified": "true",
  "more_info_url": "/testMoreInfoUrl",
  "transaction_id": "testTxId",
  "message": "testMessage",
  "refunded": "true",
  "withdraw_anchor_account": "testWithdrawAnchorAccount",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "from_account": "testFromAccount",
  "to_account": "testToAccount",
  "request_asset_code": "testRequestAssetCode",
  "request_asset_issuer": "testRequestAssetIssuer",
  "web_auth_account": "testWebAuthAccount",
  "web_auth_account_memo": "testWebAuthAccountMemo",
  "client_domain": "testClientDomain",
  "claimable_balance_supported": "true",
  "amount_expected": "testAmountExpected",
  "refund_memo": "testRefundMemo",
  "refund_memo_type": "testRefundMemoType"
}
"""

  private val sep24DepositRequest =
    """
  {
  "id": "testId",
  "memo": "testMemo",
  "memoType": "testMemoType",
  "protocol": "24",
  "toAccount": "testToAccount",
  "amount": "testAmountOut",
  "asset": "testAmountOutAsset",
  "kind": "deposit"
}
"""

  private val sep24WithdrawalEntity =
    """
{
  "id" : "testId",
  "status": "pending_user_transfer_start",
  "updated_at": "2022-04-18T14:00:00.000Z",
  "amount_in": "testAmountIn",
  "amount_in_asset": "testAmountInAsset",
  "amount_out": "testAmountOut",
  "amount_out_asset": "testAmountOutAsset",
  "amount_fee": "testAmountFee",
  "amount_fee_asset": "testAmountFeeAsset",
  "started_at": "2022-04-18T14:00:00.000Z",
  "completed_at": "2022-04-18T14:00:00.000Z",
  "transfer_received_at": "2022-04-18T14:00:00.000Z",
  "stellar_transaction_id": "testStellarTransactionId",
  "external_transaction_id": "testExternalTransactionId",
  "kind": "withdrawal",
  "status_eta":  "1",
  "kyc_verified": "true",
  "more_info_url": "/testMoreInfoUrl",
  "transaction_id": "testTxId",
  "message": "testMessage",
  "refunded": "true",
  "withdraw_anchor_account": "testWithdrawAnchorAccount",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "from_account": "testFromAccount",
  "to_account": "testToAccount",
  "request_asset_code": "testRequestAssetCode",
  "request_asset_issuer": "testRequestAssetIssuer",
  "web_auth_account": "testWebAuthAccount",
  "web_auth_account_memo": "testWebAuthAccountMemo",
  "client_domain": "testClientDomain",
  "claimable_balance_supported": "true",
  "amount_expected": "testAmountExpected",
  "refund_memo": "testRefundMemo",
  "refund_memo_type": "testRefundMemoType"
}  
"""

  private val sep24WithdrawalRequest =
    """
{
  "id": "testId",
  "memo": "testMemo",
  "memoType": "testMemoType",
  "protocol": "24",
  "fromAccount": "testFromAccount",
  "toAccount": "testWithdrawAnchorAccount",
  "amount": "testAmountExpected",
  "asset": "testAmountInAsset",
  "kind": "withdrawal"
}  
"""

  private val sep31Entity =
    """
  {
  "id" : "testId",
  "status": "pending_sender",
  "updated_at": "2022-04-18T14:00:00.000Z",
  "amount_in": "testAmountIn",
  "amount_in_asset": "testAmountInAsset",
  "amount_out": "testAmountOut",
  "amount_out_asset": "testAmountOutAsset",
  "amount_fee": "testAmountFee",
  "amount_fee_asset": "testAmountFeeAsset",
  "started_at": "2022-04-18T14:00:00.000Z",
  "completed_at": "2022-04-18T14:00:00.000Z",
  "transfer_received_at": "2022-04-18T14:00:00.000Z",
  "stellar_transaction_id": "testStellarTransactionId",
  "external_transaction_id": "testExternalTransactionId",
  "kind": "receive",
  "status_eta":  "1",
  "from_account": "testStellarAccountId",
  "to_account": "testStellarAccountId",
  "stellar_memo": "testStellarMemo",
  "stellar_memo_type": "testStellarMemoType",
  "quote_id": "testQuoteId",
  "client_domain": "testClientDomain",
  "sender_id": "testSenderId",
  "receiver_id": "testReceiverId",
  "required_info_message": "testRequiredInfoMessage",
  "refunded": "true",
  "amount_expected": "testAmountExpected"
}
"""

  private val sep31Request =
    """
  {
  "id": "testId",
  "memo": "testStellarMemo",
  "memoType": "testStellarMemoType",
  "protocol": "31",
  "toAccount": "testStellarAccountId",
  "amount": "testAmountIn",
  "asset": "testAmountInAsset",
  "kind": "receive"
}
"""
}
