package org.stellar.anchor.platform.custody.fireblocks

import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.security.Signature
import java.util.*
import kotlin.test.assertEquals
import org.apache.commons.lang3.StringUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.custody.fireblocks.FireblocksEventObject
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails
import org.stellar.anchor.api.custody.fireblocks.TransactionStatus
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.horizon.Horizon
import org.stellar.anchor.platform.config.FireblocksConfig
import org.stellar.anchor.platform.config.PropertyCustodySecretConfig
import org.stellar.anchor.platform.custody.CustodyPayment
import org.stellar.anchor.platform.custody.Sep24CustodyPaymentHandler
import org.stellar.anchor.platform.custody.Sep31CustodyPaymentHandler
import org.stellar.anchor.platform.custody.fireblocks.FireblocksEventService.FIREBLOCKS_SIGNATURE_HEADER
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo
import org.stellar.anchor.platform.utils.RSAUtil
import org.stellar.anchor.platform.utils.RSAUtil.RSA_ALGORITHM
import org.stellar.anchor.platform.utils.RSAUtil.SHA512_WITH_RSA_ALGORITHM
import org.stellar.anchor.util.FileUtil.getResourceFileAsString
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.Server
import org.stellar.sdk.requests.PaymentsRequestBuilder
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PathPaymentStrictReceiveOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse
import org.stellar.sdk.responses.operations.SetTrustLineFlagsOperationResponse

class FireblocksEventServiceTest {

  private val gson = GsonUtils.getInstance()

  private lateinit var secretConfig: PropertyCustodySecretConfig
  private lateinit var custodyTransactionRepo: JdbcCustodyTransactionRepo
  private lateinit var sep24CustodyPaymentHandler: Sep24CustodyPaymentHandler
  private lateinit var sep31CustodyPaymentHandler: Sep31CustodyPaymentHandler
  private lateinit var horizon: Horizon
  private lateinit var server: Server
  private lateinit var paymentsRequestBuilder: PaymentsRequestBuilder
  private lateinit var page: Page<OperationResponse>

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    custodyTransactionRepo = mockk()
    sep24CustodyPaymentHandler = mockk()
    sep31CustodyPaymentHandler = mockk()
    horizon = mockk()
    server = mockk()
    paymentsRequestBuilder = mockk()
    page = mockk()
  }

  @Test
  fun `test handleFireblocksEvent() for valid SUBMITTED event object and signature test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )

    val signature: String =
      getResourceFileAsString("custody/fireblocks/webhook/submitted_event_valid_signature.txt")
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)
    val eventObject: String =
      getResourceFileAsString("custody/fireblocks/webhook/submitted_event_request.json")

    eventsService.handleEvent(eventObject, httpHeaders)
  }

  @Test
  fun `test handleFireblocksEvent() for valid CONFIRMING event object with no Stellar transaction test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { horizon.server } throws java.lang.RuntimeException("Horizon error")
    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      completedEventNotStellarTxnPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun `test handleFireblocksEvent() for valid FAILED event object and signature with no Stellar transaction test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val httpHeaders: Map<String, String> =
      mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to failedEventSignature)
    val eventObject: String = failedEventRequest.trimIndent()

    val paymentCapture = slot<CustodyPayment>()

    every { horizon.server } throws java.lang.RuntimeException("Horizon error")
    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      failedEventNoStellarTxnPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun `test handleFireblocksEvent() for valid COMPLETED event object and signature test with Stellar transaction unknown payment test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)
    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<SetTrustLineFlagsOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { horizon.server } returns server
    every { server.payments() } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.includeTransactions(true) } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.forTransaction("testTxHash") } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.execute() } returns page
    every { page.records } returns operationRecords
    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    verify(exactly = 0) { custodyTransactionRepo.findByExternalTxId(any()) }
  }

  @Test
  fun `test handleFireblocksEvent() for valid CONFIRMING event object and signature with Stellar transaction payment test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)
    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { horizon.server } returns server
    every { server.payments() } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.includeTransactions(true) } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.forTransaction("testTxHash") } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.execute() } returns page
    every { page.records } returns operationRecords
    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      stellarTxnPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun `test handleFireblocksEvent() for valid CONFIRMING event object and signature with Stellar transaction path payment test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)
    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PathPaymentStrictReceiveOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(pathPaymentOperationRecord, operationRecordsTypeToken)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { horizon.server } returns server
    every { server.payments() } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.includeTransactions(true) } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.forTransaction("testTxHash") } returns paymentsRequestBuilder
    every { paymentsRequestBuilder.execute() } returns page
    every { page.records } returns operationRecords
    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      stellarTxnPathPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @ParameterizedTest
  @EnumSource(
    value = TransactionStatus::class,
    mode = EnumSource.Mode.INCLUDE,
    names =
      [
        "QUEUED",
        "PENDING_AUTHORIZATION",
        "PENDING_SIGNATURE",
        "BROADCASTING",
        "PENDING_3RD_PARTY_MANUAL_APPROVAL",
        "PENDING_3RD_PARTY",
        "COMPLETED",
        "PARTIALLY_COMPLETED",
        "PENDING_AML_SCREENING",
        "REJECTED"
      ]
  )
  fun `test handleEvent() for ignored event object statuses`(status: TransactionStatus) {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )

    val eventObject = FireblocksEventObject()
    eventObject.data = TransactionDetails.builder().status(status).build()

    val eventObjectTxt = gson.toJson(eventObject, FireblocksEventObject::class.java)

    val signature: String = generateSignature(eventObjectTxt)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObjectTxt, httpHeaders)

    verify(exactly = 0) { custodyTransactionRepo.findByExternalTxId(any()) }
  }

  @Test
  fun `test handleFireblocksEvent() for invalid public key`() {
    val config = getFireblocksConfig(StringUtils.EMPTY)
    val ex =
      assertThrows<InvalidConfigException> {
        FireblocksEventService(
          custodyTransactionRepo,
          sep24CustodyPaymentHandler,
          sep31CustodyPaymentHandler,
          horizon,
          config
        )
      }
    assertEquals("Failed to generate Fireblocks public key", ex.message)
  }

  @Test
  fun `test handleFireblocksEvent() for missed fireblocks-signature header`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )

    val eventObject = StringUtils.EMPTY
    val emptyHeaders: Map<String, String> = mapOf()

    val ex =
      assertThrows<BadRequestException> { eventsService.handleEvent(eventObject, emptyHeaders) }
    assertEquals("'fireblocks-signature' header missed", ex.message)
  }

  @Test
  fun `test handleFireblocksEvent() for empty signature`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )

    val eventObject = StringUtils.EMPTY
    val httpHeaders = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to StringUtils.EMPTY)

    val ex =
      assertThrows<BadRequestException> { eventsService.handleEvent(eventObject, httpHeaders) }
    assertEquals("'fireblocks-signature' is empty", ex.message)
  }

  @Test
  fun `test handleFireblocksEvent() for invalid signature`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )

    val invalidSignature: String =
      getResourceFileAsString("custody/fireblocks/webhook/submitted_event_invalid_signature.txt")
    val eventObject: String =
      getResourceFileAsString("custody/fireblocks/webhook/submitted_event_request.json")
    val httpHeaders = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to invalidSignature)

    eventsService.handleEvent(eventObject, httpHeaders)
  }

  @Test
  fun `test handleFireblocksEvent() for invalid signature encoding`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )

    val invalidSignature = "test"
    val eventObject: String =
      getResourceFileAsString("custody/fireblocks/webhook/submitted_event_request.json")
    val httpHeaders = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to invalidSignature)

    eventsService.handleEvent(eventObject, httpHeaders)
  }

  @Test
  fun `test handleFireblocksEvent() set missing external transaction id`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        horizon,
        config
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val transactionToUpdate = slot<JdbcCustodyTransaction>()
    val externalTransactionId = "testEventId"

    every { horizon.server } throws java.lang.RuntimeException("Horizon error")
    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), any()) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns custodyTxn
    every { custodyTransactionRepo.save(capture(transactionToUpdate)) } returns custodyTxn

    eventsService.handleEvent(eventObject, httpHeaders)
    assertEquals(externalTransactionId, transactionToUpdate.captured.externalTxId)
  }

  private fun getFireblocksConfig(publicKey: String): FireblocksConfig {
    val config = FireblocksConfig(secretConfig)
    config.publicKey = publicKey
    return config
  }

  private fun generateSignature(requestBody: String): String {
    val privateKey =
      RSAUtil.generatePrivateKey(
        getResourceFileAsString("custody/fireblocks/webhook/private_key.txt"),
        RSA_ALGORITHM
      )

    val data = requestBody.toByteArray()

    val sig: Signature = Signature.getInstance(SHA512_WITH_RSA_ALGORITHM)
    sig.initSign(privateKey)
    sig.update(data)
    val signatureBytes: ByteArray = sig.sign()

    return String(Base64.getEncoder().encode(signatureBytes))
  }

  private val completedEventNotStellarTxnPayment =
    """
{
  "externalTxId": "testEventId",
  "type": "payment",
  "updatedAt": "2023-05-10T10:18:26.130Z",
  "status": "SUCCESS",
  "transactionHash": "testTxHash"
}  
"""

  private val confirmingEventRequest =
    """
{
  "type": "TRANSACTION_STATUS_UPDATED",
  "tenantId": "testTenantId",
  "timestamp": 1683713916523,
  "data": {
    "id": "testEventId",
    "createdAt": 1683713905778,
    "lastUpdated": 1683713906130,
    "assetId": "XLM_TEST",
    "source": {
      "id": "",
      "type": "UNKNOWN",
      "name": "External",
      "subType": ""
    },
    "destination": {
      "id": "1",
      "type": "VAULT_ACCOUNT",
      "name": "TestAnchor",
      "subType": ""
    },
    "amount": 15,
    "networkFee": 0.00001,
    "netAmount": 15,
    "sourceAddress": "testSourceAddress",
    "destinationAddress": "testDestinationAddress",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "CONFIRMING",
    "txHash": "testTxHash",
    "subStatus": "CONFIRMED",
    "signedBy": [],
    "createdBy": "",
    "rejectedBy": "",
    "amountUSD": 1.33,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 15,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "customerRefId": null,
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "15",
      "requestedAmount": "15",
      "netAmount": "15",
      "amountUSD": "1.33"
    },
    "feeInfo": {
      "networkFee": "0.00001"
    },
    "destinations": [],
    "externalTxId": null,
    "blockInfo": {
      "blockHeight": "921353",
      "blockHash": "testBlockHash"
    },
    "signedMessages": [],
    "index": 0,
    "assetType": "BASE_ASSET"
  }
}
"""

  private val custodyTransaction =
    """
{
  "id": "testId",
  "status": "created",
  "amount_": "testAmount",
  "amount_asset": "testAmountAsset",
  "memo": "testMemo",
  "memo_type": "testMemoType",
  "protocol": "24",
  "from_account": "testFromAccount",
  "to_account": "testToAccount",
  "kind": "deposit"
}  
"""

  private val failedEventNoStellarTxnPayment =
    """
{
  "externalTxId": "testEventId",
  "type": "payment",
  "updatedAt": "2023-05-10T10:18:26.130Z",
  "status": "ERROR",
  "transactionHash": "testTxHash",
  "message": "THIRD_PARTY_FAILED"
}
"""

  private val failedEventRequest =
    """{
  "type": "TRANSACTION_STATUS_UPDATED",
  "tenantId": "testTenantId",
  "timestamp": 1683713916523,
  "data": {
    "id": "testEventId",
    "createdAt": 1683713905778,
    "lastUpdated": 1683713906130,
    "assetId": "XLM_TEST",
    "source": {
      "id": "",
      "type": "UNKNOWN",
      "name": "External",
      "subType": ""
    },
    "destination": {
      "id": "1",
      "type": "VAULT_ACCOUNT",
      "name": "TestAnchor",
      "subType": ""
    },
    "amount": 15,
    "networkFee": 0.00001,
    "netAmount": 15,
    "sourceAddress": "testSourceAddress",
    "destinationAddress": "testDestinationAddress",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "FAILED",
    "txHash": "testTxHash",
    "subStatus": "3RD_PARTY_FAILED",
    "signedBy": [],
    "createdBy": "",
    "rejectedBy": "",
    "amountUSD": 1.33,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 15,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "customerRefId": null,
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "15",
      "requestedAmount": "15",
      "netAmount": "15",
      "amountUSD": "1.33"
    },
    "feeInfo": {
      "networkFee": "0.00001"
    },
    "destinations": [],
    "externalTxId": null,
    "blockInfo": {
      "blockHeight": "921353",
      "blockHash": "testBlockHash"
    },
    "signedMessages": [],
    "index": 0,
    "assetType": "BASE_ASSET"
  }
}"""

  private val failedEventSignature =
    "WgGX+S1rfljuehHaFmHMhkCs/OxURLSOwDvrCl3IhpqilJclx/hLwDxu7fB49WD+5Reh8DSk+DREbCgjJE4OyPQWyLeiqGfk1W1PuKmn23ZnUq98CYhPn3rlZoggC9op4JR5F5dC8xVf2QrP7lRS5V32pKaoFGQPAqY/mQxRFbA="

  private val pathPaymentOperationRecord =
    """
[
  {
    "amount": "15.0000000",
    "asset_type": "native",
    "from": "testFrom",
    "to": "testTo",
    "id": 12345,
    "source_account": "testSourceAccount",
    "paging_token": "testPagingToken",
    "created_at": "2023-05-10T10:18:20Z",
    "transaction_hash": "testTxHash",
    "transaction_successful": true,
    "type": "path_payment",
    "links": {
      "effects": {
        "href": "https://horizon-testnet.stellar.org/operations/12345/effects",
        "templated": false
      },
      "precedes": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003dasc\u0026cursor\u003d12345",
        "templated": false
      },
      "self": {
        "href": "https://horizon-testnet.stellar.org/operations/12345",
        "templated": false
      },
      "succeeds": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003ddesc\u0026cursor\u003d12345",
        "templated": false
      },
      "transaction": {
        "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
        "templated": false
      }
    },
    "transaction": {
      "hash": "testTxHash",
      "ledger": 1234,
      "created_at": "2023-05-10T10:18:20Z",
      "source_account": "testSourceAccount",
      "fee_account": "testFeeAccount",
      "successful": true,
      "paging_token": "1234",
      "source_account_sequence": 12345,
      "max_fee": 100,
      "fee_charged": 100,
      "operation_count": 1,
      "envelope_xdr": "testEnvelopeXdr",
      "result_xdr": "testResultXdr",
      "result_meta_xdr": "resultMetaXdr",
      "signatures": [
        "testSignature1"
      ],
      "preconditions": {
        "time_bounds": {
          "min_time": 0,
          "max_time": 1683713997
        },
        "min_account_sequence_age": 0,
        "min_account_sequence_ledger_gap": 0
      },
      "links": {
        "account": {
          "href": "https://horizon-testnet.stellar.org/accounts/testAccount",
          "templated": false
        },
        "effects": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/effects{?cursor,limit,order}",
          "templated": true
        },
        "ledger": {
          "href": "https://horizon-testnet.stellar.org/ledgers/1234",
          "templated": false
        },
        "operations": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/operations{?cursor,limit,order}",
          "templated": true
        },
        "precedes": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003dasc\u0026cursor\u003d12345",
          "templated": false
        },
        "self": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
          "templated": false
        },
        "succeeds": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003ddesc\u0026cursor\u003d12345",
          "templated": false
        }
      },
      "rate_limit_limit": 0,
      "rate_limit_remaining": 0,
      "rate_limit_reset": 0
    },
    "rate_limit_limit": 0,
    "rate_limit_remaining": 0,
    "rate_limit_reset": 0
  }
]  
"""

  private val paymentOperationRecord =
    """
[
  {
    "amount": "15.0000000",
    "asset_type": "native",
    "from": "testFrom",
    "to": "testTo",
    "id": 12345,
    "source_account": "testSourceAccount",
    "paging_token": "testPagingToken",
    "created_at": "2023-05-10T10:18:20Z",
    "transaction_hash": "testTxHash",
    "transaction_successful": true,
    "type": "payment",
    "links": {
      "effects": {
        "href": "https://horizon-testnet.stellar.org/operations/12345/effects",
        "templated": false
      },
      "precedes": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003dasc\u0026cursor\u003d12345",
        "templated": false
      },
      "self": {
        "href": "https://horizon-testnet.stellar.org/operations/12345",
        "templated": false
      },
      "succeeds": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003ddesc\u0026cursor\u003d12345",
        "templated": false
      },
      "transaction": {
        "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
        "templated": false
      }
    },
    "transaction": {
      "hash": "testTxHash",
      "ledger": 1234,
      "created_at": "2023-05-10T10:18:20Z",
      "source_account": "testSourceAccount",
      "fee_account": "testFeeAccount",
      "successful": true,
      "paging_token": "1234",
      "source_account_sequence": 12345,
      "max_fee": 100,
      "fee_charged": 100,
      "operation_count": 1,
      "envelope_xdr": "testEnvelopeXdr",
      "result_xdr": "testResultXdr",
      "result_meta_xdr": "resultMetaXdr",
      "signatures": [
        "testSignature1"
      ],
      "preconditions": {
        "time_bounds": {
          "min_time": 0,
          "max_time": 1683713997
        },
        "min_account_sequence_age": 0,
        "min_account_sequence_ledger_gap": 0
      },
      "links": {
        "account": {
          "href": "https://horizon-testnet.stellar.org/accounts/testAccount",
          "templated": false
        },
        "effects": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/effects{?cursor,limit,order}",
          "templated": true
        },
        "ledger": {
          "href": "https://horizon-testnet.stellar.org/ledgers/1234",
          "templated": false
        },
        "operations": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/operations{?cursor,limit,order}",
          "templated": true
        },
        "precedes": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003dasc\u0026cursor\u003d12345",
          "templated": false
        },
        "self": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
          "templated": false
        },
        "succeeds": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003ddesc\u0026cursor\u003d12345",
          "templated": false
        }
      },
      "rate_limit_limit": 0,
      "rate_limit_remaining": 0,
      "rate_limit_reset": 0
    },
    "rate_limit_limit": 0,
    "rate_limit_remaining": 0,
    "rate_limit_reset": 0
  }
]  
"""

  private val stellarTxnPathPayment =
    """
{
  "id": "12345",
  "externalTxId": "testEventId",
  "type": "path_payment",
  "from": "testFrom",
  "to": "testTo",
  "amount": "15.0000000",
  "assetType": "native",
  "assetCode": "native",
  "assetName": "native",
  "updatedAt": "2023-05-10T10:18:26.130Z",
  "status": "SUCCESS",
  "transactionHash": "testTxHash",
  "transactionMemoType": "none",
  "transactionEnvelope": "testEnvelopeXdr"
}  
"""

  private val stellarTxnPayment =
    """
{
  "id": "12345",
  "externalTxId": "testEventId",
  "type": "payment",
  "from": "testFrom",
  "to": "testTo",
  "amount": "15.0000000",
  "assetType": "native",
  "assetCode": "native",
  "assetName": "native",
  "updatedAt": "2023-05-10T10:18:26.130Z",
  "status": "SUCCESS",
  "transactionHash": "testTxHash",
  "transactionMemoType": "none",
  "transactionEnvelope": "testEnvelopeXdr"
}  
"""
}
