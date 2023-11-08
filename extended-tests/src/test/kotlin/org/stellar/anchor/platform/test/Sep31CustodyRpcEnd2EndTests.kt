package org.stellar.anchor.platform.test

import io.ktor.client.plugins.*
import io.ktor.http.*
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.stellar.anchor.CLIENT_WALLET_SECRET
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
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.info
import org.stellar.anchor.util.MemoHelper
import org.stellar.anchor.util.Sep1Helper
import org.stellar.reference.client.AnchorReferenceServerClient
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.walletsdk.ApplicationConfiguration
import org.stellar.walletsdk.StellarConfiguration
import org.stellar.walletsdk.Wallet
import org.stellar.walletsdk.anchor.MemoType
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.asset.StellarAssetId
import org.stellar.walletsdk.horizon.SigningKeyPair
import org.stellar.walletsdk.horizon.sign

class Sep31CustodyRpcEnd2EndTests(
  config: TestConfig,
  val toml: Sep1Helper.TomlContent,
  val jwt: String
) {
  private val gson = GsonUtils.getInstance()
  private val walletSecretKey = System.getenv("WALLET_SECRET_KEY") ?: CLIENT_WALLET_SECRET
  private val keypair = SigningKeyPair.fromSecret(walletSecretKey)
  private val wallet =
    Wallet(
      StellarConfiguration.Testnet,
      ApplicationConfiguration { defaultRequest { url { protocol = URLProtocol.HTTP } } }
    )
  private val maxTries = 90
  private val anchorReferenceServerClient =
    AnchorReferenceServerClient(Url(config.env["reference.server.url"]!!))
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))
  private val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), jwt)
  private val sep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), jwt)
  private val sep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), jwt)
  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)

  private fun assertEvents(
    actualEvents: List<SendEventRequest>?,
    expectedStatuses: List<Pair<AnchorEvent.Type, SepTransactionStatus>>
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
    expectedStatuses: List<Pair<AnchorEvent.Type, SepTransactionStatus>>
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

  private fun `test typical receive end-to-end flow`(asset: StellarAssetId, amount: String) =
    runBlocking {
      walletServerClient.clearCallbacks()
      anchorReferenceServerClient.clearEvents()

      val senderCustomerRequest =
        gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
      val senderCustomer = sep12Client.putCustomer(senderCustomerRequest)

      // Create receiver customer
      val receiverCustomerRequest =
        gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
      val receiverCustomer = sep12Client.putCustomer(receiverCustomerRequest)

      val quote = sep38Client.postQuote(asset.sep38, amount, FIAT_USD)

      // POST Sep31 transaction
      val txnRequest = gson.fromJson(postSep31TxnRequest, Sep31PostTransactionRequest::class.java)
      txnRequest.senderId = senderCustomer!!.id
      txnRequest.receiverId = receiverCustomer!!.id
      txnRequest.quoteId = quote.id
      val postTxResponse = sep31Client.postTransaction(txnRequest)

      anchorReferenceServerClient.processSep31Receive(postTxResponse.id)

      // Get transaction status and make sure it is PENDING_SENDER
      val transaction = platformApiClient.getTransaction(postTxResponse.id)
      assertEquals(SepTransactionStatus.PENDING_SENDER, transaction.status)

      val memoType: MemoType =
        when (postTxResponse.stellarMemoType) {
          MemoHelper.memoTypeAsString(org.stellar.sdk.xdr.MemoType.MEMO_ID) -> {
            MemoType.ID
          }
          MemoHelper.memoTypeAsString(org.stellar.sdk.xdr.MemoType.MEMO_HASH) -> {
            MemoType.HASH
          }
          else -> {
            MemoType.TEXT
          }
        }

      // Submit transfer transaction
      val transfer =
        wallet
          .stellar()
          .transaction(keypair)
          .transfer(postTxResponse.stellarAccountId, asset, amount)
          .setMemo(Pair(memoType, postTxResponse.stellarMemo))
          .build()
      transfer.sign(keypair)
      wallet.stellar().submitTransaction(transfer)

      // Wait for the status to change to COMPLETED
      waitStatus(postTxResponse.id, SepTransactionStatus.COMPLETED)

      // Check the events sent to the reference server are recorded correctly
      val actualEvents = waitForBusinessServerEvents(postTxResponse.id, 3)
      assertEvents(actualEvents, expectedStatuses)

      // Check the callbacks sent to the wallet reference server are recorded correctly
      val actualCallbacks = waitForWalletServerCallbacks(postTxResponse.id, 3)
      assertCallbacks(actualCallbacks, expectedStatuses)
    }

  private suspend fun waitForWalletServerCallbacks(
    txnId: String,
    count: Int
  ): List<Sep31GetTransactionResponse>? {
    var retries = 5
    var callbacks: List<Sep31GetTransactionResponse>? = null
    while (retries > 0) {
      callbacks = walletServerClient.getCallbacks(txnId, Sep31GetTransactionResponse::class.java)
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
    count: Int
  ): List<SendEventRequest>? {
    var retries = 5
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
        info("Deposit transaction status changed to $status. Message: ${transaction.message}")
      }

      delay(1.seconds)

      if (transaction.status == expectedStatus) {
        return
      }
    }

    fail("Transaction wasn't $expectedStatus in $maxTries tries, last status: $status")
  }

  fun testAll() {
    info("Running SEP-31 USDC end-to-end tests...")
    `test typical receive end-to-end flow`(USDC, "5")
  }

  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5")
    private const val FIAT_USD = "iso4217:USD"
    private val expectedStatuses =
      listOf(
        TRANSACTION_CREATED to SepTransactionStatus.PENDING_SENDER,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_RECEIVER,
        TRANSACTION_STATUS_CHANGED to SepTransactionStatus.COMPLETED
      )
  }

  private val postSep31TxnRequest =
    """{
    "amount": "5",
    "asset_code": "USDC",
    "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
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
