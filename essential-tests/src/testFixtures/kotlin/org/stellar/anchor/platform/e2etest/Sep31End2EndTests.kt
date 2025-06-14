package org.stellar.anchor.platform.e2etest

import io.ktor.http.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import org.stellar.anchor.api.callback.SendEventRequest
import org.stellar.anchor.api.callback.SendEventRequestPayload
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_CREATED
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.platform.*
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.info
import org.stellar.reference.client.AnchorReferenceServerClient
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.asset.IssuedAssetId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class Sep31End2EndTests : IntegrationTestBase(TestConfig()) {

  private val gson = GsonUtils.getInstance()
  private val maxTries = 60
  private val anchorReferenceServerClient =
    AnchorReferenceServerClient(Url(config.env["reference.server.url"]!!))
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))
  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)
  private val clientWalletAccount = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId

  private fun assertEvents(
    actualEvents: List<SendEventRequest>?,
    expectedStatuses: List<Pair<AnchorEvent.Type, SepTransactionStatus>>,
  ) {
    assertNotNull(actualEvents)
    actualEvents?.let {
      assertEquals(expectedStatuses.size, actualEvents.size)

      expectedStatuses.forEachIndexed { index, expectedStatus ->
        actualEvents[index].let { actualEvent ->
          assertNotNull(actualEvent.id)
          assertNotNull(actualEvent.timestamp)
          assertEquals(expectedStatus.first.type, actualEvent.type)
          Assertions.assertTrue(actualEvent.payload is SendEventRequestPayload)
          assertEquals(expectedStatus.second, actualEvent.payload.transaction.status)
        }
      }
    }
  }

  private fun assertCallbacks(
    actualCallbacks: List<Sep31GetTransactionResponse>?,
    expectedStatuses: List<Pair<AnchorEvent.Type, SepTransactionStatus>>,
  ) {
    assertNotNull(actualCallbacks)
    actualCallbacks?.let {
      assertEquals(expectedStatuses.size, actualCallbacks.size)

      expectedStatuses.forEachIndexed { index, expectedStatus ->
        actualCallbacks[index].let { actualCallback ->
          assertNotNull(actualCallback.transaction.id)
          assertEquals(expectedStatus.second.status, actualCallback.transaction.status)
        }
      }
    }
  }

  @Test
  fun `test typical receive end to end flow`() = runBlocking {
    val asset = USDC
    val amount = "5"

    walletServerClient.clearCallbacks()
    val wallet = WalletClient(clientWalletAccount, CLIENT_WALLET_SECRET, null, toml)

    val senderCustomerRequest =
      gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    val senderCustomer = wallet.sep12.putCustomer(senderCustomerRequest)

    // Create receiver customer
    val receiverCustomerRequest =
      gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = wallet.sep12.putCustomer(receiverCustomerRequest)

    val quote = wallet.sep38.postQuote(asset.sep38, amount, FIAT_USD)

    // POST Sep31 transaction
    val txnRequest = gson.fromJson(postSep31TxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.senderId = senderCustomer!!.id
    txnRequest.receiverId = receiverCustomer!!.id
    txnRequest.quoteId = quote.id
    txnRequest.fundingMethod = "SWIFT"
    val postTxResponse = wallet.sep31.postTransaction(txnRequest)
    info("POST /transaction initiated ${postTxResponse.id}")

    // Get transaction status and make sure it is PENDING_SENDER
    waitStatus(postTxResponse.id, SepTransactionStatus.PENDING_SENDER)
    val transaction = wallet.sep31.getTransaction(postTxResponse.id).transaction

    // Submit transfer transaction
    info("Transferring $amount $asset to ${transaction.stellarAccountId}")
    transactionWithRetry {
      wallet.send(
        transaction.stellarAccountId,
        Asset.create(asset.id),
        amount,
        transaction.stellarMemo,
        transaction.stellarMemoType,
      )
    }
    info("Transfer complete")
    waitStatus(postTxResponse.id, SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE)

    // Supply missing KYC info to continue with the transaction
    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(receiverCustomer.id, "sep31-receiver", postTxResponse.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    wallet.sep12.putCustomer(
      gson.fromJson(
        gson.toJson(additionalRequiredFields.associateWith { receiverKycInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    )
    info("Submitting additional KYC info $additionalRequiredFields")

    // Wait for the status to change to COMPLETED
    waitStatus(postTxResponse.id, SepTransactionStatus.COMPLETED)

    // Check the events sent to the reference server are recorded correctly
    val actualEvents = waitForBusinessServerEvents(postTxResponse.id, 5)
    assertEvents(actualEvents, expectedStatuses)

    // Check the callbacks sent to the wallet reference server are recorded correctly
    val actualCallbacks = waitForWalletServerCallbacks(postTxResponse.id, 5)
    assertCallbacks(actualCallbacks, expectedStatuses)
  }

  private suspend fun waitForWalletServerCallbacks(
    txnId: String,
    count: Int,
  ): List<Sep31GetTransactionResponse>? {
    var retries = 30
    var callbacks: List<Sep31GetTransactionResponse>? = null
    while (retries > 0) {
      callbacks =
        walletServerClient.getTransactionCallbacks(
          "sep31",
          txnId,
          Sep31GetTransactionResponse::class.java,
        )
      if (callbacks.size == count) {
        return callbacks
      }
      delay(1.seconds)
      retries--
    }
    return callbacks
  }

  private suspend fun waitForBusinessServerEvents(
    txnId: String,
    count: Int,
  ): List<SendEventRequest>? {
    var retries = 30
    var events: List<SendEventRequest>? = null
    while (retries > 0) {
      events = anchorReferenceServerClient.getEvents(txnId)
      if (events.size == count) {
        return events
      }
      delay(1.seconds)
      retries--
    }
    return events
  }

  private suspend fun waitStatus(id: String, expectedStatus: SepTransactionStatus) {
    var status: SepTransactionStatus? = null

    for (i in 0..maxTries) {
      // Get transaction info
      val transaction = platformApiClient.getTransaction(id)

      val current = transaction.status
      info("Expected: $expectedStatus. Current: $current")
      if (status != transaction.status) {
        status = transaction.status
        "Transaction(${transaction.id}) status changed to ${status}. Message: ${transaction.message}"
      }

      delay(1.seconds)

      if (transaction.status == expectedStatus) {
        return
      }
    }

    fail("Transaction wasn't $expectedStatus in $maxTries tries, last status: $status")
  }

  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
    private const val FIAT_USD = "iso4217:USD"
    private val expectedStatuses =
      listOf(
        TRANSACTION_CREATED to SepTransactionStatus.PENDING_RECEIVER,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_SENDER,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_RECEIVER,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_RECEIVER,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.COMPLETED,
      )
  }

  private val receiverKycInfo =
    mapOf(
      "bank_account_number" to "13719713158835300",
      "bank_account_type" to "checking",
      "bank_number" to "123",
      "bank_branch_number" to "121122676",
    )

  private val postSep31TxnRequest =
    """{
    "amount": "5",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "receiver_id": "MOCK_RECEIVER_ID",
    "sender_id": "MOCK_SENDER_ID",
    "fields": {
        "transaction": {
            "receiver_routing_number": "r0123",
            "receiver_account_number": "a0456",
            "type": "SWIFT"
        }
    }
}"""
}
