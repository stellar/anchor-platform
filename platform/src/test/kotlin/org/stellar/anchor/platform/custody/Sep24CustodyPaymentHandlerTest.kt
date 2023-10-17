package org.stellar.anchor.platform.custody

import io.micrometer.core.instrument.Counter
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo
import org.stellar.anchor.platform.service.AnchorMetrics.PAYMENT_RECEIVED
import org.stellar.anchor.platform.service.AnchorMetrics.PAYMENT_SENT
import org.stellar.anchor.util.GsonUtils

class Sep24CustodyPaymentHandlerTest {

  @MockK(relaxed = true) private lateinit var custodyTransactionRepo: JdbcCustodyTransactionRepo

  @MockK(relaxed = true) private lateinit var platformApiClient: PlatformApiClient

  @MockK(relaxed = true) private lateinit var paymentReceivedCounter: Counter

  @MockK(relaxed = true) private lateinit var paymentSentCounter: Counter

  @MockK(relaxed = true) private lateinit var rpcConfig: RpcConfig

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  private lateinit var sep24CustodyPaymentHandler: Sep24CustodyPaymentHandler

  private val gson = GsonUtils.getInstance()

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    sep24CustodyPaymentHandler =
      Sep24CustodyPaymentHandler(
        custodyTransactionRepo,
        platformApiClient,
        rpcConfig,
        metricsService
      )
  }

  @Test
  fun test_handleEvent_onReceived_payment_success() {
    val txn =
      gson.fromJson(
        custodyTransactionInputSep24WithdrawalPayment,
        JdbcCustodyTransaction::class.java
      )
    val payment = gson.fromJson(custodyPaymentWithId, CustodyPayment::class.java)

    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns txn
    every { metricsService.counter(PAYMENT_RECEIVED, "asset", "testAmountInAsset") } returns
      paymentReceivedCounter

    sep24CustodyPaymentHandler.onReceived(txn, payment)

    verify(exactly = 1) { paymentReceivedCounter.increment(1.0000000) }
    verify(exactly = 1) {
      platformApiClient.notifyOnchainFundsReceived(
        txn.id,
        payment.transactionHash,
        payment.amount,
        "payment received"
      )
    }

    JSONAssert.assertEquals(
      custodyTransactionDbSep24WithdrawalPayment,
      gson.toJson(custodyTxCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_handleEvent_onReceived_refund_success() {
    val txn =
      gson.fromJson(
        custodyTransactionInputSep24WithdrawalRefund,
        JdbcCustodyTransaction::class.java
      )
    val payment = gson.fromJson(custodyPaymentWithId, CustodyPayment::class.java)

    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns txn

    sep24CustodyPaymentHandler.onReceived(txn, payment)

    verify(exactly = 0) { metricsService.counter(PAYMENT_RECEIVED, any(), any()) }
    verify(exactly = 1) {
      platformApiClient.notifyRefundSent(
        txn.id,
        payment.transactionHash,
        payment.amount,
        txn.amountFee,
        txn.asset
      )
    }

    JSONAssert.assertEquals(
      custodyTransactionDbSep24WithdrawalRefund,
      gson.toJson(custodyTxCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_handleEvent_onReceived_payment_error() {
    val txn =
      gson.fromJson(
        custodyTransactionInputSep24WithdrawalPayment,
        JdbcCustodyTransaction::class.java
      )
    val payment = gson.fromJson(custodyPaymentWithIdError, CustodyPayment::class.java)

    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every { rpcConfig.customMessages.custodyTransactionFailed } returns "payment failed"
    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns txn

    sep24CustodyPaymentHandler.onReceived(txn, payment)

    verify(exactly = 0) { metricsService.counter(PAYMENT_SENT, any(), any()) }
    verify(exactly = 1) { platformApiClient.notifyTransactionError(txn.id, "payment failed") }

    JSONAssert.assertEquals(
      custodyTransactionDbSep24WithdrawalPaymentError,
      gson.toJson(custodyTxCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_handleEvent_onSent_success() {
    val txn =
      gson.fromJson(custodyTransactionInputSep24DepositPayment, JdbcCustodyTransaction::class.java)
    val payment = gson.fromJson(custodyPaymentWithId, CustodyPayment::class.java)

    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every { rpcConfig.customMessages.outgoingPaymentSent } returns "payment sent"
    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns txn
    every { metricsService.counter(PAYMENT_SENT, "asset", "testAmountInAsset") } returns
      paymentSentCounter

    sep24CustodyPaymentHandler.onSent(txn, payment)

    verify(exactly = 1) { paymentSentCounter.increment(1.0000000) }
    verify(exactly = 1) {
      platformApiClient.notifyOnchainFundsSent(txn.id, payment.transactionHash, "payment sent")
    }

    JSONAssert.assertEquals(
      custodyTransactionDbSep24DepositPayment,
      gson.toJson(custodyTxCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_handleEvent_onSent_payment_error() {
    val txn =
      gson.fromJson(custodyTransactionInputSep24DepositPayment, JdbcCustodyTransaction::class.java)
    val payment = gson.fromJson(custodyPaymentWithIdError, CustodyPayment::class.java)

    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every { rpcConfig.customMessages.custodyTransactionFailed } returns "payment failed"
    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns txn

    sep24CustodyPaymentHandler.onSent(txn, payment)

    verify(exactly = 0) { metricsService.counter(PAYMENT_SENT, any(), any()) }
    verify(exactly = 1) { platformApiClient.notifyTransactionError(txn.id, "payment failed") }

    JSONAssert.assertEquals(
      custodyTransactionDbSep24DepositPaymentError,
      gson.toJson(custodyTxCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  private val custodyPaymentWithId =
    """
{
  "id": "12345",
  "externalTxId": "testEventId",
  "type": "payment",
  "from": "testFrom",
  "to": "testTo",
  "amount": "1.0000000",
  "assetType": "credit_alphanum4",
  "assetName": "testAmountInAsset",
  "updatedAt": "2023-05-10T10:18:25.778Z",
  "status": "SUCCESS",
  "transactionHash": "testTxHash",
  "transactionMemoType": "none",
  "transactionEnvelope": "testEnvelopeXdr"
}  
"""

  private val custodyPaymentWithIdError =
    """
  {
  "id": "12345",
  "externalTxId": "testEventId",
  "type": "payment",
  "from": "testFrom",
  "to": "testTo",
  "amount": "1.0000000",
  "assetType": "credit_alphanum4",
  "assetName": "testAmountInAsset",
  "updatedAt": "2023-05-10T10:18:25.778Z",
  "status": "ERROR",
  "transactionHash": "testTxHash",
  "transactionMemoType": "none",
  "transactionEnvelope": "testEnvelopeXdr"
}
"""

  private val custodyTransactionDbSep24DepositPayment =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "external_tx_id": "testEventId",
  "status": "completed",
  "amount": "1",
  "asset": "stellar:testAmountInAsset",
  "updated_at": "2023-05-10T10:18:25.778Z",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFrom",
  "to_account": "testToAccount",
  "kind": "deposit",
  "reconciliation_attempt_count": 0,
  "type": "payment"
}  
"""

  private val custodyTransactionDbSep24DepositPaymentError =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "external_tx_id": "testEventId",
  "status": "failed",
  "amount": "1",
  "asset": "stellar:testAmountInAsset",
  "updated_at": "2023-05-10T10:18:25.778Z",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFrom",
  "to_account": "testToAccount",
  "kind": "deposit",
  "reconciliation_attempt_count": 0,
  "type": "payment"
}  
"""

  private val custodyTransactionDbSep24WithdrawalPayment =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "external_tx_id": "testEventId",
  "status": "completed",
  "amount": "1",
  "asset": "stellar:testAmountInAsset",
  "updated_at": "2023-05-10T10:18:25.778Z",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFrom",
  "to_account": "testToAccount",
  "kind": "withdrawal",
  "reconciliation_attempt_count": 0,
  "type": "payment"
}
"""

  private val custodyTransactionDbSep24WithdrawalPaymentError =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "external_tx_id": "testEventId",
  "status": "failed",
  "amount": "1",
  "asset": "stellar:testAmountInAsset",
  "updated_at": "2023-05-10T10:18:25.778Z",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFrom",
  "to_account": "testToAccount",
  "kind": "withdrawal",
  "reconciliation_attempt_count": 0,
  "type": "payment"
}  
"""

  private val custodyTransactionDbSep24WithdrawalRefund =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "external_tx_id": "testEventId",
  "status": "completed",
  "amount": "1",
  "amount_fee": "0.1",
  "asset": "stellar:testAmountInAsset",
  "updated_at": "2023-05-10T10:18:25.778Z",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFrom",
  "to_account": "testToAccount",
  "kind": "withdrawal",
  "reconciliation_attempt_count": 0,
  "type": "refund"
}  
"""

  private val custodyTransactionInputSep24DepositPayment =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "status": "submitted",
  "amount": "1",
  "asset": "stellar:testAmountInAsset",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFromAccount1",
  "to_account": "testToAccount",
  "kind": "deposit",
  "type": "payment"
}
"""

  private val custodyTransactionInputSep24WithdrawalPayment =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "status": "submitted",
  "amount": "1",
  "asset": "stellar:testAmountInAsset",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFromAccount1",
  "to_account": "testToAccount",
  "kind": "withdrawal",
  "type": "payment"
}  
"""

  private val custodyTransactionInputSep24WithdrawalRefund =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "status": "submitted",
  "amount": "1",
  "amount_fee": "0.1",
  "asset": "stellar:testAmountInAsset",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFromAccount1",
  "to_account": "testToAccount",
  "kind": "withdrawal",
  "type": "refund"
}
"""
}
