package org.stellar.anchor.platform.custody.fireblocks

import io.mockk.*
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
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.*
import org.stellar.anchor.platform.config.FireblocksConfig
import org.stellar.anchor.platform.config.PropertyCustodySecretConfig
import org.stellar.anchor.platform.custody.CustodyPayment
import org.stellar.anchor.platform.custody.Sep24CustodyPaymentHandler
import org.stellar.anchor.platform.custody.Sep31CustodyPaymentHandler
import org.stellar.anchor.platform.custody.Sep6CustodyPaymentHandler
import org.stellar.anchor.platform.custody.fireblocks.FireblocksEventService.FIREBLOCKS_SIGNATURE_HEADER
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo
import org.stellar.anchor.util.FileUtil.getResourceFileAsString
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.RSAUtil
import org.stellar.anchor.util.RSAUtil.RSA_ALGORITHM
import org.stellar.anchor.util.RSAUtil.SHA512_WITH_RSA_ALGORITHM
import org.stellar.sdk.Server
import org.stellar.sdk.requests.PaymentsRequestBuilder
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.xdr.*
import org.stellar.sdk.xdr.MemoType.MEMO_ID

class FireblocksEventServiceTest {

  private val gson = GsonUtils.getInstance()

  private lateinit var secretConfig: PropertyCustodySecretConfig
  private lateinit var custodyTransactionRepo: JdbcCustodyTransactionRepo
  private lateinit var sep6CustodyPaymentHandler: Sep6CustodyPaymentHandler
  private lateinit var sep24CustodyPaymentHandler: Sep24CustodyPaymentHandler
  private lateinit var sep31CustodyPaymentHandler: Sep31CustodyPaymentHandler
  //  private lateinit var horizon: Horizon
  private lateinit var ledgerClient: LedgerClient
  private lateinit var server: Server
  private lateinit var paymentsRequestBuilder: PaymentsRequestBuilder
  private lateinit var page: Page<OperationResponse>

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    custodyTransactionRepo = mockk()
    sep6CustodyPaymentHandler = mockk()
    sep24CustodyPaymentHandler = mockk()
    sep31CustodyPaymentHandler = mockk()
    ledgerClient = mockk()
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      completedEventNotStellarTxnPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun `test handleFireblocksEvent() for valid FAILED event object and signature with no Stellar transaction test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val httpHeaders: Map<String, String> =
      mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to failedEventSignature)
    val eventObject: String = failedEventRequest.trimIndent()

    val paymentCapture = slot<CustodyPayment>()

    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      failedEventNoStellarTxnPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun `test handleFireblocksEvent() for valid COMPLETED event object and signature test with Stellar transaction unknown payment test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { ledgerClient.getTransaction("testTxHash") } returns
      getMockLedgerTransaction(::getTrustlineOperations)

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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)
    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { ledgerClient.getTransaction("testTxHash") } returns
      getMockLedgerTransaction(::getPaymentOperations)

    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)
    JSONAssert.assertEquals(
      stellarTxnPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT,
    )
  }

  @Test
  fun `test handleFireblocksEvent() for valid CONFIRMING event object and signature with Stellar transaction path payment test`() {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)
    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val paymentCapture = slot<CustodyPayment>()

    every { ledgerClient.getTransaction("testTxHash") } returns
      getMockLedgerTransaction(::getPathPaymentOperations)

    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), capture(paymentCapture)) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns null

    eventsService.handleEvent(eventObject, httpHeaders)

    JSONAssert.assertEquals(
      stellarTxnPathPayment,
      gson.toJson(paymentCapture.captured),
      JSONCompareMode.STRICT,
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
        "REJECTED",
      ],
  )
  fun `test handleEvent() for ignored event object statuses`(status: TransactionStatus) {
    val config =
      getFireblocksConfig(getResourceFileAsString("custody/fireblocks/webhook/public_key.txt"))
    val eventsService =
      FireblocksEventService(
        custodyTransactionRepo,
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
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
          sep6CustodyPaymentHandler,
          sep24CustodyPaymentHandler,
          sep31CustodyPaymentHandler,
          ledgerClient,
          config,
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
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
        sep6CustodyPaymentHandler,
        sep24CustodyPaymentHandler,
        sep31CustodyPaymentHandler,
        ledgerClient,
        config,
      )
    val custodyTxn = gson.fromJson(custodyTransaction, JdbcCustodyTransaction::class.java)

    val eventObject: String = confirmingEventRequest.trimIndent()
    val signature: String = generateSignature(eventObject)
    val httpHeaders: Map<String, String> = mutableMapOf(FIREBLOCKS_SIGNATURE_HEADER to signature)

    val transactionToUpdate = slot<JdbcCustodyTransaction>()
    val externalTransactionId = "testEventId"

    every { custodyTransactionRepo.findByExternalTxId(any()) } returns custodyTxn
    every { sep24CustodyPaymentHandler.onSent(eq(custodyTxn), any()) } just runs
    every {
      custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(any(), any())
    } returns custodyTxn
    every { custodyTransactionRepo.save(capture(transactionToUpdate)) } returns custodyTxn

    eventsService.handleEvent(eventObject, httpHeaders)
    assertEquals(externalTransactionId, transactionToUpdate.captured.externalTxId)
  }

  private fun getTrustlineOperations(): List<LedgerOperation> {
    return listOf(LedgerOperation.builder().type(OperationType.SET_TRUST_LINE_FLAGS).build())
  }

  private fun getPaymentOperations(): List<LedgerOperation> {
    return listOf(
      LedgerOperation.builder()
        .type(OperationType.PAYMENT)
        .paymentOperation(
          LedgerPaymentOperation.builder()
            .id("12345")
            .from("testFrom")
            .to("testTo")
            .amount(150000000)
            .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
            .build()
        )
        .build()
    )
  }

  private fun getPathPaymentOperations(): List<LedgerOperation> {
    return listOf(
      LedgerOperation.builder()
        .type(OperationType.PATH_PAYMENT_STRICT_RECEIVE)
        .pathPaymentOperation(
          LedgerPathPaymentOperation.builder()
            .id("12345")
            .from("testFrom")
            .to("testTo")
            .sourceAsset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
            .sourceAmount(150000000L)
            .sourceAccount("testSourceAccount")
            .amount(150000000)
            .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
            .build()
        )
        .build()
    )
  }

  private fun getMockLedgerTransaction(
    getOperations: () -> List<LedgerOperation>
  ): LedgerTransaction {
    val memoId = Uint64()
    memoId.uint64 = XdrUnsignedHyperInteger(12345L)

    return builder()
      .hash("testTxHash")
      .sourceAccount("testSourceAccount")
      .memo(Memo.builder().discriminant(MEMO_ID).id(memoId).build())
      .envelopeXdr("testEnvelopeXdr")
      .operations(getOperations())
      .build()
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
        RSA_ALGORITHM,
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
  "transactionMemo": "12345",
  "transactionMemoType": "id",
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
  "transactionMemo": "12345",
  "transactionMemoType": "id",
  "transactionEnvelope": "testEnvelopeXdr"
}  
"""
}
