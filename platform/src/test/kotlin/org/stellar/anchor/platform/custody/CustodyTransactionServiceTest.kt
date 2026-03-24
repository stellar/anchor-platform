package org.stellar.anchor.platform.custody

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.Customization
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.comparator.CustomComparator
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest
import org.stellar.anchor.api.custody.CreateTransactionRefundRequest
import org.stellar.anchor.api.exception.FireblocksException
import org.stellar.anchor.api.exception.custody.CustodyBadRequestException
import org.stellar.anchor.api.exception.custody.CustodyNotFoundException
import org.stellar.anchor.api.exception.custody.CustodyServiceUnavailableException
import org.stellar.anchor.api.exception.custody.CustodyTooManyRequestsException
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.platform.data.JdbcCustodyTransaction.PaymentType.PAYMENT
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo
import org.stellar.anchor.util.GsonUtils

class CustodyTransactionServiceTest {

  companion object {
    private const val TRANSACTION_ID = "TRANSACTION_ID"
    private const val REFUND_TRANSACTION_ID = "REFUND_TRANSACTION_ID"
    private const val REQUEST_BODY = "REQUEST_BODY"
  }

  private val gson = GsonUtils.getInstance()

  @MockK(relaxed = true) private lateinit var custodyTransactionRepo: JdbcCustodyTransactionRepo
  @MockK(relaxed = true) private lateinit var custodyPaymentService: CustodyPaymentService<Any>

  private lateinit var custodyTransactionService: CustodyTransactionService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    custodyTransactionService =
      CustodyTransactionService(custodyTransactionRepo, custodyPaymentService)
  }

  @Test
  fun test_create_success() {
    val request =
      gson.fromJson(createCustodyTransactionRequest, CreateCustodyTransactionRequest::class.java)
    val entityCapture = slot<JdbcCustodyTransaction>()

    every { custodyTransactionRepo.save(capture(entityCapture)) } returns null

    custodyTransactionService.create(request, PAYMENT)

    val actualCustodyTransaction = entityCapture.captured
    assertTrue(!Instant.now().isBefore(actualCustodyTransaction.createdAt))
    actualCustodyTransaction.createdAt = null
    JSONAssert.assertEquals(
      createCustodyTransactionEntity,
      gson.toJson(entityCapture.captured),
      CustomComparator(JSONCompareMode.STRICT, Customization("id") { _, _ -> true })
    )
  }

  @Test
  fun test_createPayment_transaction_does_not_exist() {
    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(any(), any())
    } returns null

    val exception =
      assertThrows<CustodyNotFoundException> {
        custodyTransactionService.createPayment(TRANSACTION_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Transaction (id=TRANSACTION_ID) is not found", exception.message)

    verify(exactly = 0) { custodyPaymentService.createTransactionPayment(any(), any()) }
  }

  @Test
  fun test_createPayment_transaction_exists() {
    val transaction = JdbcCustodyTransaction()
    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns null

    custodyTransactionService.createPayment(TRANSACTION_ID, REQUEST_BODY)

    verify(exactly = 1) {
      custodyPaymentService.createTransactionPayment(transaction, REQUEST_BODY)
    }
  }

  @Test
  fun test_createPayment_bad_request() {
    val transaction = JdbcCustodyTransaction()
    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns null
    every { custodyPaymentService.createTransactionPayment(transaction, REQUEST_BODY) } throws
      FireblocksException("Bad request", 400)

    val ex =
      assertThrows<CustodyBadRequestException> {
        custodyTransactionService.createPayment(TRANSACTION_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Bad request", ex.message)
  }

  @Test
  fun test_createPayment_too_many_requests() {
    val transaction = JdbcCustodyTransaction()
    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns null
    every { custodyPaymentService.createTransactionPayment(transaction, REQUEST_BODY) } throws
      FireblocksException("Too many requests", 429)

    val ex =
      assertThrows<CustodyTooManyRequestsException> {
        custodyTransactionService.createPayment(TRANSACTION_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Too many requests", ex.message)
  }

  @Test
  fun test_createPayment_service_unavailable() {
    val transaction = JdbcCustodyTransaction()
    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns null
    every { custodyPaymentService.createTransactionPayment(transaction, REQUEST_BODY) } throws
      FireblocksException("Service unavailable", 503)

    val ex =
      assertThrows<CustodyServiceUnavailableException> {
        custodyTransactionService.createPayment(TRANSACTION_ID, REQUEST_BODY)
      }
    Assertions.assertEquals("Service unavailable", ex.message)
  }

  @Test
  fun test_createPayment_unexpected_status_code() {
    val transaction = JdbcCustodyTransaction()
    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns null
    every { custodyPaymentService.createTransactionPayment(transaction, REQUEST_BODY) } throws
      FireblocksException("Forbidden", 403)

    val ex =
      assertThrows<FireblocksException> {
        custodyTransactionService.createPayment(TRANSACTION_ID, REQUEST_BODY)
      }
    Assertions.assertEquals(
      "Fireblocks API returned an error. HTTP status[403], response[Forbidden]",
      ex.message
    )
  }

  @Test
  fun test_createRefund_transaction_does_not_exist() {
    val request = gson.fromJson(refundRequest, CreateTransactionRefundRequest::class.java)

    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(any(), any())
    } returns null

    val exception =
      assertThrows<CustodyNotFoundException> {
        custodyTransactionService.createRefund(TRANSACTION_ID, request)
      }
    Assertions.assertEquals("Transaction (id=TRANSACTION_ID) is not found", exception.message)

    verify(exactly = 0) { custodyPaymentService.createTransactionPayment(any(), any()) }
    verify(exactly = 0) { custodyTransactionRepo.deleteById(any()) }
  }

  @Test
  fun test_createRefund_transaction_exists() {
    val request = gson.fromJson(refundRequest, CreateTransactionRefundRequest::class.java)
    val transaction = gson.fromJson(custodyTransactionPayment, JdbcCustodyTransaction::class.java)
    val refundTransaction = JdbcCustodyTransaction()
    refundTransaction.id = REFUND_TRANSACTION_ID
    val custodyTxCapture = slot<JdbcCustodyTransaction>()

    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(capture(custodyTxCapture)) } returns refundTransaction

    custodyTransactionService.createRefund(TRANSACTION_ID, request)

    verify(exactly = 1) { custodyPaymentService.createTransactionPayment(refundTransaction, null) }
  }

  @Test
  fun test_createRefund_bad_request() {
    val request = gson.fromJson(refundRequest, CreateTransactionRefundRequest::class.java)
    val transaction = JdbcCustodyTransaction()
    val refundTransaction = JdbcCustodyTransaction()
    refundTransaction.id = REFUND_TRANSACTION_ID

    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns refundTransaction
    every { custodyPaymentService.createTransactionPayment(refundTransaction, null) } throws
      FireblocksException("Bad request", 400)

    val ex =
      assertThrows<CustodyBadRequestException> {
        custodyTransactionService.createRefund(TRANSACTION_ID, request)
      }
    Assertions.assertEquals("Bad request", ex.message)

    verify(exactly = 1) { custodyTransactionRepo.deleteById(REFUND_TRANSACTION_ID) }
  }

  @Test
  fun test_createRefund_too_many_requests() {
    val request = gson.fromJson(refundRequest, CreateTransactionRefundRequest::class.java)
    val transaction = JdbcCustodyTransaction()
    val refundTransaction = JdbcCustodyTransaction()
    refundTransaction.id = REFUND_TRANSACTION_ID

    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns refundTransaction
    every { custodyPaymentService.createTransactionPayment(refundTransaction, null) } throws
      FireblocksException("Too many requests", 429)

    val ex =
      assertThrows<CustodyTooManyRequestsException> {
        custodyTransactionService.createRefund(TRANSACTION_ID, request)
      }
    Assertions.assertEquals("Too many requests", ex.message)

    verify(exactly = 1) { custodyTransactionRepo.deleteById(REFUND_TRANSACTION_ID) }
  }

  @Test
  fun test_createRefund_service_unavailable() {
    val request = gson.fromJson(refundRequest, CreateTransactionRefundRequest::class.java)
    val transaction = JdbcCustodyTransaction()
    val refundTransaction = JdbcCustodyTransaction()
    refundTransaction.id = REFUND_TRANSACTION_ID

    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns refundTransaction
    every { custodyPaymentService.createTransactionPayment(refundTransaction, null) } throws
      FireblocksException("Service unavailable", 503)

    val ex =
      assertThrows<CustodyServiceUnavailableException> {
        custodyTransactionService.createRefund(TRANSACTION_ID, request)
      }
    Assertions.assertEquals("Service unavailable", ex.message)

    verify(exactly = 1) { custodyTransactionRepo.deleteById(REFUND_TRANSACTION_ID) }
  }

  @Test
  fun test_createRefund_unexpected_status_code() {
    val request = gson.fromJson(refundRequest, CreateTransactionRefundRequest::class.java)
    val transaction = JdbcCustodyTransaction()
    val refundTransaction = JdbcCustodyTransaction()
    refundTransaction.id = REFUND_TRANSACTION_ID

    every {
      custodyTransactionRepo.findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(
        TRANSACTION_ID,
        PAYMENT.type
      )
    } returns transaction
    every { custodyTransactionRepo.save(any()) } returns refundTransaction
    every { custodyPaymentService.createTransactionPayment(refundTransaction, null) } throws
      FireblocksException("Forbidden", 403)

    val ex =
      assertThrows<FireblocksException> {
        custodyTransactionService.createRefund(TRANSACTION_ID, request)
      }
    Assertions.assertEquals(
      "Fireblocks API returned an error. HTTP status[403], response[Forbidden]",
      ex.message
    )

    verify(exactly = 1) { custodyTransactionRepo.deleteById(REFUND_TRANSACTION_ID) }
  }

  private val createCustodyTransactionEntity =
    """
{
  "id": "testId",
  "sep_tx_id": "testId",
  "status": "created",
  "amount": "testAmount",
  "asset": "testAmountAsset",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "testProtocol",
  "from_account": "testFromAccount",
  "to_account": "testToAccount",
  "kind": "testKind",
  "reconciliation_attempt_count": 0,
  "type":"payment"
}            
"""

  private val createCustodyTransactionRequest =
    """
{
  "id" : "testId",
  "memo":  "testMemo",
  "memoType": "testMemoType",
  "protocol": "testProtocol",
  "fromAccount": "testFromAccount",
  "toAccount": "testToAccount",
  "amount": "testAmount",
  "asset": "testAmountAsset",
  "kind": "testKind",
  "requestAssetCode": "testRequestAssetCode",
  "requestAssetIssuer": "testRequestAssetIssuer",
  "type":"payment"
}          
"""

  private val custodyTransactionPayment =
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

  private val refundRequest =
    """
{
  "memo": "testMemo",
  "memoType": "testMemoType",
  "amount": "testAmount",
  "amountFee": "testAmountFee"
}  
"""
}
