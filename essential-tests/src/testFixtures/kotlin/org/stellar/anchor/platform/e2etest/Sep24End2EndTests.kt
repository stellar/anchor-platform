package org.stellar.anchor.platform.e2etest

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.util.stream.Stream
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.web.util.UriComponentsBuilder
import org.stellar.anchor.api.callback.SendEventRequest
import org.stellar.anchor.api.callback.SendEventRequestPayload
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_CREATED
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.debug
import org.stellar.anchor.util.Log.info
import org.stellar.reference.client.AnchorReferenceServerClient
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.walletsdk.InteractiveFlowResponse
import org.stellar.walletsdk.anchor.*
import org.stellar.walletsdk.anchor.TransactionStatus.*
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.asset.StellarAssetId
import org.stellar.walletsdk.asset.XLM
import org.stellar.walletsdk.auth.AuthToken
import org.stellar.walletsdk.horizon.SigningKeyPair
import org.stellar.walletsdk.horizon.sign

const val WITHDRAW_FUND_CLIENT_SECRET_1 = "SCGHF6KF6CBQ6Z4ZZUMU4DGRM6LR2PS7XOUN5VOETMPTPLD5BQE2FKL3"
const val WITHDRAW_FUND_CLIENT_SECRET_2 = "SBSO7FVRDHCETSGPYETIFNVK64LS4KH325GOAENGV5Z7L6FAP7YS2BPK"
const val DEPOSIT_FUND_CLIENT_SECRET_1 = "SDNZAK6LCYNR4HYEFBZY3I2KLRDLSCE5RCF6HZ2KBBC7JLCFNZAHJCBQ"
const val DEPOSIT_FUND_CLIENT_SECRET_2 = "SCW2SJEPTL4K7FFPFOFABFEFZJCG6LHULWVJX6JLIJ7TYIKTL6P473HM"

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
open class Sep24End2EndTests : AbstractIntegrationTests(TestConfig()) {
  private val client = HttpClient {
    install(HttpTimeout) {
      requestTimeoutMillis = 300000
      connectTimeoutMillis = 300000
      socketTimeoutMillis = 300000
    }
  }
  private val maxTries = 30
  private val anchorReferenceServerClient =
    AnchorReferenceServerClient(Url(config.env["reference.server.url"]!!))
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))
  private val jwtService: JwtService =
    JwtService(
      config.env["secret.sep6.more_info_url.jwt_secret"]!!,
      config.env["secret.sep10.jwt_secret"]!!,
      config.env["secret.sep24.interactive_url.jwt_secret"]!!,
      config.env["secret.sep24.more_info_url.jwt_secret"]!!,
      config.env["secret.callback_api.auth_secret"]!!,
      config.env["secret.platform_api.auth_secret"]!!,
      config.env["secret.custody_server.auth_secret"]!!
    )

  @ParameterizedTest
  @MethodSource("depositAssetsAndAmounts")
  fun `test typical deposit end-to-end flow`(
    walletSecretKey: String,
    asset: StellarAssetId,
    amount: String
  ) = runBlocking {
    val keypair = SigningKeyPair.fromSecret(walletSecretKey)
    walletServerClient.clearCallbacks()

    val token = anchor.auth().authenticate(keypair)
    val response = makeDeposit(asset, amount, token)

    // Assert the interactive URL JWT is valid
    val params = UriComponentsBuilder.fromUriString(response.url).build().queryParams
    val cipher = params["token"]!![0]
    val interactiveJwt = jwtService.decode(cipher, Sep24InteractiveUrlJwt::class.java)
    assertEquals(keypair.address, interactiveJwt.sub)
    assertEquals(amount, (interactiveJwt.claims["data"] as Map<*, *>)["amount"], amount)

    // Wait for the status to change to COMPLETED
    waitForTxnStatus(response.id, COMPLETED, token)

    // Check if the transaction can be listed by stellar transaction id
    val fetchedTxn = anchor.interactive().getTransaction(response.id, token) as DepositTransaction
    val transactionByStellarId =
      anchor
        .interactive()
        .getTransactionBy(token, stellarTransactionId = fetchedTxn.stellarTransactionId)
    assertEquals(fetchedTxn.id, transactionByStellarId.id)

    // Check the events sent to the reference server are recorded correctly
    val actualEvents = waitForBusinessServerEvents(response.id, getExpectedDepositStatus().size)
    assertEvents(actualEvents, getExpectedDepositStatus())

    // Check the callbacks sent to the wallet reference server are recorded correctly
    val actualCallbacks = waitForWalletServerCallbacks(response.id, getExpectedDepositStatus().size)
    assertCallbacks(actualCallbacks, getExpectedDepositStatus())
  }

  open fun getExpectedDepositStatus(): List<Pair<AnchorEvent.Type, SepTransactionStatus>> {
    return listOf(
      TRANSACTION_CREATED to SepTransactionStatus.INCOMPLETE,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_USR_TRANSFER_START,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_ANCHOR,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_STELLAR,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.COMPLETED
    )
  }

  open fun getExpectedWithdrawalStatus(): List<Pair<AnchorEvent.Type, SepTransactionStatus>> {
    return listOf(
      TRANSACTION_CREATED to SepTransactionStatus.INCOMPLETE,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_USR_TRANSFER_START,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_ANCHOR,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.PENDING_EXTERNAL,
      TRANSACTION_STATUS_CHANGED to SepTransactionStatus.COMPLETED
    )
  }

  private suspend fun makeDeposit(
    asset: StellarAssetId,
    amount: String,
    token: AuthToken
  ): InteractiveFlowResponse {
    // Start interactive deposit
    val deposit = anchor.interactive().deposit(asset, token, mapOf("amount" to amount))

    // Get transaction status and make sure it is INCOMPLETE
    val transaction = anchor.interactive().getTransaction(deposit.id, token)
    assertEquals(INCOMPLETE, transaction.status)
    // Make sure the interactive url is valid. This will also start the reference server's
    // withdrawal process.
    val resp = client.get(deposit.url)
    info("accessing ${deposit.url}...")
    assertEquals(200, resp.status.value)

    return deposit
  }

  private fun assertEvents(
    actualEvents: List<SendEventRequest>?,
    expectedStatuses: List<Pair<AnchorEvent.Type, SepTransactionStatus>>
  ) {
    assertNotNull(actualEvents)
    actualEvents?.let {
      assertEquals(expectedStatuses.size, actualEvents.size)

      GsonUtils.getInstance().toJson(expectedStatuses).let { json ->
        println("expectedStatuses: $json")
      }

      GsonUtils.getInstance().toJson(actualEvents).let { json -> println("actualEvents: $json") }

      expectedStatuses.forEachIndexed { index, expectedStatus ->
        actualEvents[index].let { actualEvent ->
          assertNotNull(actualEvent.id)
          assertNotNull(actualEvent.timestamp)
          assertEquals(expectedStatus.first.type, actualEvent.type)
          assertTrue(actualEvent.payload is SendEventRequestPayload)
          assertEquals(expectedStatus.second, actualEvent.payload.transaction.status)
        }
      }
    }
  }

  private fun assertCallbacks(
    actualCallbacks: List<Sep24GetTransactionResponse>?,
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

  @ParameterizedTest
  @MethodSource("withdrawAssetsAndAmounts")
  fun `test typical withdraw end-to-end flow`(
    walletSecretKey: String,
    asset: StellarAssetId,
    amount: String
  ) {
    `test typical withdraw end-to-end flow`(walletSecretKey, asset, mapOf("amount" to amount))
  }

  private fun `test typical withdraw end-to-end flow`(
    walletSecretKey: String,
    asset: StellarAssetId,
    extraFields: Map<String, String>
  ) = runBlocking {
    val keypair = SigningKeyPair.fromSecret(walletSecretKey)
    walletServerClient.clearCallbacks()

    val token = anchor.auth().authenticate(keypair)
    val withdrawTxn = anchor.interactive().withdraw(asset, token, extraFields)

    // Get transaction status and make sure it is INCOMPLETE
    val transaction = anchor.interactive().getTransaction(withdrawTxn.id, token)
    assertEquals(INCOMPLETE, transaction.status)
    // Make sure the interactive url is valid. This will also start the reference server's
    // withdrawal process.
    val resp = client.get(withdrawTxn.url)
    info("accessing ${withdrawTxn.url}...")
    assertEquals(200, resp.status.value)
    // Wait for the status to change to PENDING_USER_TRANSFER_START
    waitForTxnStatus(withdrawTxn.id, PENDING_USER_TRANSFER_START, token)
    // Submit transfer transaction
    val walletTxn =
      (anchor.interactive().getTransaction(withdrawTxn.id, token) as WithdrawalTransaction)
    transactionWithRetry {
      val transfer =
        wallet
          .stellar()
          .transaction(walletTxn.from!!)
          .transferWithdrawalTransaction(walletTxn, asset)
          .build()
      transfer.sign(keypair)

      wallet.stellar().submitTransaction(transfer)
    }
    // Wait for the status to change to PENDING_USER_TRANSFER_END
    waitForTxnStatus(withdrawTxn.id, COMPLETED, token)

    // Check if the transaction can be listed by stellar transaction id
    val fetchTxn =
      anchor.interactive().getTransaction(withdrawTxn.id, token) as WithdrawalTransaction
    val transactionByStellarId =
      anchor
        .interactive()
        .getTransactionBy(token, stellarTransactionId = fetchTxn.stellarTransactionId)
    assertEquals(fetchTxn.id, transactionByStellarId.id)

    // Check the events sent to the reference server are recorded correctly
    val actualEvents =
      waitForBusinessServerEvents(withdrawTxn.id, getExpectedWithdrawalStatus().size)
    assertEvents(actualEvents, getExpectedWithdrawalStatus())

    // Check the callbacks sent to the wallet reference server are recorded correctly
    val actualCallbacks = waitForWalletServerCallbacks(withdrawTxn.id, 5)
    assertCallbacks(actualCallbacks, getExpectedWithdrawalStatus())
  }

  private suspend fun waitForWalletServerCallbacks(
    txnId: String,
    count: Int
  ): List<Sep24GetTransactionResponse>? {
    var retries = 30
    var callbacks: List<Sep24GetTransactionResponse>? = null
    while (retries > 0) {
      callbacks = walletServerClient.getCallbacks(txnId, Sep24GetTransactionResponse::class.java)
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

  private suspend fun waitForTxnStatus(
    id: String,
    expectedStatus: TransactionStatus,
    token: AuthToken,
    exitStatus: TransactionStatus = ERROR
  ) {
    var status: TransactionStatus? = null

    for (i in 0..maxTries) {
      // Get transaction info
      val transaction = anchor.interactive().getTransaction(id, token)
      if (status != transaction.status) {
        status = transaction.status
        info(
          "Transaction(id=${transaction.id}) status changed to $status. Message: ${transaction.message}"
        )
      }

      if (transaction.status == expectedStatus) return

      if (transaction.status == exitStatus) break

      delay(1.seconds)
    }

    fail("Transaction wasn't $expectedStatus in $maxTries tries, last status: $status")
  }

  @ParameterizedTest
  @MethodSource("historyAssetsAndAmounts")
  fun `test created transactions show up in the get history call`(
    walletSecretKey: String,
    asset: StellarAssetId,
    amount: String
  ) = runBlocking {
    val keypair = SigningKeyPair.fromSecret(walletSecretKey)
    val newAcc = wallet.stellar().account().createKeyPair()

    transactionWithRetry {
      val tx =
        wallet
          .stellar()
          .transaction(keypair)
          .sponsoring(keypair, newAcc) {
            createAccount(newAcc)
            addAssetSupport(USDC)
          }
          .build()
          .sign(keypair)
          .sign(newAcc)

      wallet.stellar().submitTransaction(tx)
    }
    delay(5000)
    val token = anchor.auth().authenticate(newAcc)
    val deposits =
      (0..1).map {
        val txnId = makeDeposit(asset, amount, token).id
        waitForTxnStatus(txnId, COMPLETED, token)
        txnId
      }
    val history = anchor.interactive().getTransactionsForAsset(asset, token)
    assertThat(history).allMatch { deposits.contains(it.id) }
    debug("test created transactions show up in the get history call passed")
  }

  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")

    @JvmStatic
    fun depositAssetsAndAmounts(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(DEPOSIT_FUND_CLIENT_SECRET_1, USDC, "1"),
        Arguments.of(DEPOSIT_FUND_CLIENT_SECRET_2, XLM, "0.0001")
      )
    }

    @JvmStatic
    fun withdrawAssetsAndAmounts(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(WITHDRAW_FUND_CLIENT_SECRET_1, USDC, "0.01"),
        Arguments.of(WITHDRAW_FUND_CLIENT_SECRET_2, XLM, "0.0001")
      )
    }

    @JvmStatic
    fun historyAssetsAndAmounts(): Stream<Arguments> {
      return Stream.of(Arguments.of(CLIENT_WALLET_SECRET, USDC, "1"))
    }
  }
}
