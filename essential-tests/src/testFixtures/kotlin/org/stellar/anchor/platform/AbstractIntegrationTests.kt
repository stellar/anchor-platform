package org.stellar.anchor.platform

import io.ktor.client.plugins.*
import io.ktor.http.*
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerClientHelper.toLedgerOperation
import org.stellar.anchor.ledger.LedgerClientHelper.waitForTransactionAvailable
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.util.Log.info
import org.stellar.anchor.util.MemoHelper
import org.stellar.anchor.util.Sep1Helper.TomlContent
import org.stellar.anchor.util.Sep1Helper.parse
import org.stellar.sdk.*
import org.stellar.sdk.operations.Operation.fromXdrAmount
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.xdr.TransactionEnvelope
import org.stellar.walletsdk.ApplicationConfiguration
import org.stellar.walletsdk.StellarConfiguration
import org.stellar.walletsdk.Wallet
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.auth.AuthToken
import org.stellar.walletsdk.horizon.SigningKeyPair

private lateinit var testPaymentValues: List<Pair<String, String>>

abstract class AbstractIntegrationTests(val config: TestConfig) {
  companion object {
    const val JSON_RPC_VERSION = "2.0"

    // the search and replace values are injected into the expected values
    const val TX_ID_KEY = "%TX_ID%"
    const val RECEIVER_ID_KEY = "%RECEIVER_ID%"
    const val SENDER_ID_KEY = "%SENDER_ID%"

    // the test payment values
    const val TEST_PAYMENT_MEMO = "22bf7341574e4b1082516a2e84a8"
    const val TEST_PAYMENT_DEST_ACCOUNT = "GBDYDBJKQBJK4GY4V7FAONSFF2IBJSKNTBYJ65F5KCGBY2BIGPGGLJOH"
    const val TEST_PAYMENT_ASSET_CIRCLE_USDC =
      "USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    // custody deposit address
    const val CUSTODY_DEST_ACCOUNT = "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O"
  }

  var toml: TomlContent =
    parse(resourceAsString("${config.env["anchor.domain"]}/.well-known/stellar.toml"))
  var wallet =
    Wallet(
      StellarConfiguration.Testnet,
      ApplicationConfiguration { defaultRequest { url { protocol = URLProtocol.HTTP } } },
    )
  var walletKeyPair = SigningKeyPair.fromSecret(CLIENT_WALLET_SECRET)
  var anchor = wallet.anchor(config.env["anchor.domain"]!!)
  var token: AuthToken

  private val submissionLock = Mutex()
  private val testPaymentKey: KeyPair =
    KeyPair.fromSecretSeed(config.get("secret.sep10.signing.seed"))

  fun inject(target: String, vararg replacements: Pair<String, String>): String {
    var result = target
    for ((search, replace) in replacements) {
      result = result.replace(search, replace)
    }

    for ((search, replace) in getTestPaymentValues()) {
      result = result.replace(search, replace)
    }

    return result.trimIndent()
  }

  private fun getTestPaymentValues(): List<Pair<String, String>> {
    if (!::testPaymentValues.isInitialized || testPaymentValues.isEmpty()) {
      if (config.get("stellar_network.rpc_url") != null) {
        val ledgerClient = StellarRpc(config.get("stellar_network.rpc_url")!!)
        val ledgerTxn = sendTestPayment(ledgerClient)
        setTestPaymentsValues(ledgerTxn!!)
      } else if (config.get("stellar_network.horizon_url") != null) {
        val horizonServer = Server(config.get("stellar_network.horizon_url")!!)
        // not the most optimized way to do this, but it works
        val payment = fetchTestPaymentFromHorizon(horizonServer)
        val ledgerTxn: LedgerTransaction? =
          if (payment != null) {
            toLedgerTransaction(payment)
          } else {
            val ledgerClient = Horizon(config.get("stellar_network.horizon_url")!!)
            sendTestPayment(ledgerClient)
          }
        setTestPaymentsValues(ledgerTxn!!)
      } else {
        throw Exception("None of stellar_network.rpc_url or stellar_network.horizon_url is not set")
      }
    }
    return testPaymentValues
  }

  private fun toLedgerTransaction(operationResponse: OperationResponse): LedgerTransaction {
    val txnResponse = operationResponse.transaction
    return LedgerTransaction.builder()
      .hash(txnResponse.hash)
      .ledger(txnResponse.ledger)
      .applicationOrder(TOID.fromInt64(txnResponse.pagingToken.toLong()).transactionOrder)
      .sourceAccount(txnResponse.sourceAccount)
      .envelopeXdr(txnResponse.envelopeXdr)
      .memo(MemoHelper.toXdr(txnResponse.memo))
      .sequenceNumber(txnResponse.sourceAccountSequence)
      .createdAt(Instant.parse(txnResponse.createdAt))
      .operations(listOf(toLedgerOperation(operationResponse)))
      .build()
  }

  private fun sendTestPayment(ledgerClient: LedgerClient): LedgerTransaction? {
    val destAccount = TEST_PAYMENT_DEST_ACCOUNT

    // send test payment of 1 USDC from distribution account to the test receiver account
    val usdcAsset =
      Asset.create(null, "USDC", "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5")
        as AssetTypeCreditAlphaNum
    val sourceKey = KeyPair.fromSecretSeed(config.get("app..payment.signing.seed"))
    info(
      "Create test payment transaction: 1 USDC from distribution account to the test receiver account"
    )
    val accountId = sourceKey.accountId
    val account = ledgerClient.getAccount(accountId)
    val txn =
      TransactionBuilder(Account(accountId, account.sequenceNumber), Network.TESTNET)
        .addOperation(
          PaymentOperation.builder()
            .sourceAccount(sourceKey.accountId)
            .destination(destAccount)
            .asset(usdcAsset)
            .amount(BigDecimal("0.0002"))
            .build()
        )
        .addMemo(Memo.text(TEST_PAYMENT_MEMO)) // Add memo
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(180)).build()
        )
        .setBaseFee(Transaction.MIN_BASE_FEE)
        .build()
    // Sign the transaction
    txn.sign(sourceKey)

    val response = ledgerClient.submitTransaction(txn)
    return waitForTransactionAvailable(ledgerClient, response.hash)
  }

  private fun setTestPaymentsValues(ledgerTxn: LedgerTransaction) {
    val txnEnv = TransactionEnvelope.fromXdrBase64(ledgerTxn.envelopeXdr)
    val paymentOp = txnEnv.v1.tx.operations[0].body.paymentOp
    if (paymentOp != null) {
      // initialize the test payment value pairs for injection
      testPaymentValues =
        listOf(
          Pair(
            "%TESTPAYMENT_ID%",
            TOID(ledgerTxn.ledger.toInt(), ledgerTxn.applicationOrder, 1).toInt64().toString(),
          ),
          Pair("%TESTPAYMENT_AMOUNT%", fromXdrAmount(paymentOp.amount.int64).toString()),
          Pair("%TESTPAYMENT_TXN_HASH%", ledgerTxn.hash),
          Pair("%TESTPAYMENT_SRC_ACCOUNT%", ledgerTxn.operations[0].paymentOperation.from),
          Pair(
            "%TESTPAYMENT_DEST_ACCOUNT%",
            StrKey.encodeEd25519PublicKey(paymentOp.destination.ed25519.uint256),
          ),
          Pair("%TESTPAYMENT_ASSET_CIRCLE_USDC%", TEST_PAYMENT_ASSET_CIRCLE_USDC),
          Pair("%CUSTODY_DEST_ACCOUNT%", CUSTODY_DEST_ACCOUNT),
        )
    }
  }

  private fun fetchTestPaymentFromHorizon(horizonServer: Server): OperationResponse? {
    if (config.get("stellar_network.horizon_url") == null) {
      throw Exception("stellar_network.horizon_url is not set")
    }

    val destAccount = TEST_PAYMENT_DEST_ACCOUNT
    val payments =
      horizonServer
        .payments()
        .forAccount(destAccount)
        .order(RequestBuilder.Order.DESC)
        .includeTransactions(true)
        .limit(10)
        .execute()
        .records

    // use the most recent one.
    if (payments.isEmpty()) return null
    return payments.first()
  }

  private fun sendTestPaymentToHorizon(server: Server) {
    // send test payment of 1 USDC from distribution account to the test receiver account
    val usdcAsset =
      Asset.create(null, "USDC", "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5")
        as AssetTypeCreditAlphaNum
    val sourceKey = KeyPair.fromSecretSeed(config.get("app..payment.signing.seed"))

    info(
      "Create test payment transaction: 1 USDC from distribution account to the test receiver account"
    )
    // Load the source account's current state
    val sourceAccountResponse = server.accounts().account(sourceKey.accountId)

    // Build the transaction
    val transaction =
      TransactionBuilder(sourceAccountResponse, Network.TESTNET)
        .addOperation(
          PaymentOperation.builder()
            .sourceAccount(sourceKey.accountId)
            .destination(TEST_PAYMENT_DEST_ACCOUNT)
            .asset(usdcAsset)
            .amount(BigDecimal("0.0002"))
            .build()
        )
        .addMemo(Memo.text(TEST_PAYMENT_MEMO)) // Add memo
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(180)).build()
        )
        .setBaseFee(Transaction.MIN_BASE_FEE)
        .build()

    // Sign the transaction
    transaction.sign(sourceKey)

    // Submit the transaction
    val response = server.submitTransaction(transaction)

    // Check the result
    if (response.successful) {
      info("Payment transaction successful. Transaction hash: ${response.hash}")
    } else {
      info("Payment failed. ${response.resultXdr}")
    }
  }

  suspend fun transactionWithRetry(
    maxAttempts: Int = 5,
    delay: Int = 5,
    transactionLogic: suspend () -> Unit,
  ) =
    flow<Unit> { submissionLock.withLock { transactionLogic() } }
      .retryWhen { _, attempt ->
        if (attempt < maxAttempts) {
          delay((delay + (1..5).random()).seconds)
          return@retryWhen true
        } else {
          return@retryWhen false
        }
      }
      .collect {}

  init {
    runBlocking { token = anchor.auth().authenticate(walletKeyPair) }
  }
}
