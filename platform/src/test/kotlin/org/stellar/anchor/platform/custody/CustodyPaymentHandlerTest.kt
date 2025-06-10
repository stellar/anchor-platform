package org.stellar.anchor.platform.custody

import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.info

class CustodyPaymentHandlerTest {

  // test implementation
  class CustodyPaymentHandlerTestImpl(custodyTransactionRepo: JdbcCustodyTransactionRepo) :
    CustodyPaymentHandler(custodyTransactionRepo) {
    override fun onReceived(txn: JdbcCustodyTransaction?, payment: CustodyPayment?) {
      info("Test implementation")
    }

    override fun onSent(txn: JdbcCustodyTransaction?, payment: CustodyPayment?) {
      info("Test implementation")
    }
  }

  private val gson = GsonUtils.getInstance()

  @MockK(relaxed = true) private lateinit var custodyTransactionRepo: JdbcCustodyTransactionRepo

  private lateinit var custodyPaymentHandler: CustodyPaymentHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    custodyPaymentHandler = CustodyPaymentHandlerTestImpl(custodyTransactionRepo)
  }

  @Test
  fun test_validatePayment_unsupportedType_errorStatus() {
    val txn =
      gson.fromJson(custodyTransactionInputSep24DepositPayment, JdbcCustodyTransaction::class.java)
    val payment =
      gson.fromJson(custodyPaymentUnsupportedAssetErrorStatus, CustodyPayment::class.java)

    custodyPaymentHandler.validatePayment(txn, payment)

    assertNull(payment.getMessage())
  }

  @Test
  fun test_validatePayment_validAsset() {
    val txn =
      gson.fromJson(custodyTransactionInputSep24DepositPayment, JdbcCustodyTransaction::class.java)
    val payment = gson.fromJson(custodyPaymentValidAsset, CustodyPayment::class.java)

    custodyPaymentHandler.validatePayment(txn, payment)

    assertNull(payment.getMessage())
  }

  @Test
  fun test_updateTransaction_withStellarTxId() {
    val txn =
      gson.fromJson(custodyTransactionInputSep24DepositPayment, JdbcCustodyTransaction::class.java)
    val payment = gson.fromJson(custodyPaymentWithId, CustodyPayment::class.java)

    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns txn

    custodyPaymentHandler.updateTransaction(txn, payment)

    JSONAssert.assertEquals(
      custodyTransactionDbSep24DepositPayment,
      gson.toJson(custodyTxCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  private val custodyPaymentUnsupportedAssetErrorStatus =
    """
{
  "id": "12345",
  "externalTxId": "testEventId",
  "type": "payment",
  "from": "testFrom",
  "to": "testTo",
  "amount": "1.0000000",
  "asset_type": "credit_alphanum100",
  "asset_name": "testAmountInAsset",
  "created_at": "2023-05-10T10:18:25.778Z",
  "status": "ERROR",
  "transaction_hash": "testTxHash",
  "transaction_memo_type": "none",
  "transaction_envelope": "testEnvelopeXdr"
}
"""

  private val custodyPaymentValidAsset =
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
  "createdAt": "2023-05-10T10:18:25.778Z",
  "status": "SUCCESS",
  "transactionHash": "testTxHash",
  "transactionMemoType": "none",
  "transactionEnvelope": "testEnvelopeXdr"
}
"""

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
}
