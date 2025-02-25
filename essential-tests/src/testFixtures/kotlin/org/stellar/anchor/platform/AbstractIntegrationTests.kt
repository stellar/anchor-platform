package org.stellar.anchor.platform

import io.ktor.client.plugins.*
import io.ktor.http.*
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stellar.anchor.util.Sep1Helper.TomlContent
import org.stellar.anchor.util.Sep1Helper.parse
import org.stellar.sdk.*
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.Auth.authorizeEntry
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.operations.PaymentOperationResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.SorobanAuthorizationEntry
import org.stellar.walletsdk.ApplicationConfiguration
import org.stellar.walletsdk.StellarConfiguration
import org.stellar.walletsdk.Wallet
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.auth.AuthToken
import org.stellar.walletsdk.horizon.SigningKeyPair

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

    lateinit var testPaymentValues: List<Pair<String, String>>

    fun inject(target: String, vararg replacements: Pair<String, String>): String {
      var result = target
      for ((search, replace) in replacements) {
        result = result.replace(search, replace)
      }

      for ((search, replace) in testPaymentValues) {
        result = result.replace(search, replace)
      }

      return result.trimIndent()
    }

    // fetch the test payment from the testnet
    fun fetchTestPayment() {
      val destAccount = TEST_PAYMENT_DEST_ACCOUNT
      val horizonServer = Server("https://horizon-testnet.stellar.org")
      val payments =
        horizonServer
          .payments()
          .forAccount(destAccount)
          .order(RequestBuilder.Order.DESC)
          .includeTransactions(true)
          .limit(100)
          .execute()
          .records
      for (payment in payments) {
        if (payment is PaymentOperationResponse) {
          if (payment.transaction.memo.toString() == TEST_PAYMENT_MEMO) {
            println("Found test payment")
            // initialize the test payment value pairs for injection
            testPaymentValues =
              listOf(
                Pair("%TESTPAYMENT_ID%", payment.id.toString()),
                Pair("%TESTPAYMENT_AMOUNT%", payment.amount),
                Pair("%TESTPAYMENT_TXN_HASH%", payment.transactionHash),
                Pair("%TESTPAYMENT_SRC_ACCOUNT%", payment.from),
                Pair("%TESTPAYMENT_DEST_ACCOUNT%", payment.to),
                Pair("%TESTPAYMENT_ASSET_CIRCLE_USDC%", TEST_PAYMENT_ASSET_CIRCLE_USDC),
                Pair("%CUSTODY_DEST_ACCOUNT%", CUSTODY_DEST_ACCOUNT),
              )

            return
          }
        }
      }

      println("\n*** STOP ***")
      println("Cannot find test payment")
      println(
        "Please visit the testnet reset script: https://github.com/stellar/useful-scripts to create the test payment"
      )
      throw Exception("Cannot find test payment")
    }
  }

  init {
    // fetch the test payment
    fetchTestPayment()
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
  var rpc = SorobanServer("https://soroban-testnet.stellar.org")

  fun transferFunds(
    source: String,
    destination: String,
    asset: Asset,
    amount: String,
    signer: KeyPair,
  ): String {
    val parameters =
      mutableListOf(
        // from=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(source).address)
          .build(),
        // to=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(destination).address)
          .build(),
        SCVal.builder()
          .discriminant(SCValType.SCV_I128)
          .i128(Scv.toInt128(BigInteger.valueOf(amount.toLong() * 10000000)).i128)
          .build(),
      )
    val operation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(Network.TESTNET),
          "transfer",
          parameters,
        )
        .build()

    var account = rpc.getAccount(walletKeyPair.keyPair.accountId)
    val transaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(operation)
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val simulationResponse = rpc.simulateTransaction(transaction)
    val signedAuthEntries = mutableListOf<SorobanAuthorizationEntry>()
    simulationResponse.results.forEach {
      it.auth.forEach { entryXdr ->
        val entry = SorobanAuthorizationEntry.fromXdrBase64(entryXdr)
        val validUntilLedgerSeq = simulationResponse.latestLedger + 10

        val signedEntry = authorizeEntry(entry, signer, validUntilLedgerSeq, Network.TESTNET)
        signedAuthEntries.add(signedEntry)
      }
    }

    val signedOperation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(Network.TESTNET),
          "transfer",
          parameters,
        )
        .sourceAccount(walletKeyPair.keyPair.accountId)
        .auth(signedAuthEntries)
        .build()

    account = rpc.getAccount(walletKeyPair.keyPair.accountId)
    val authorizedTransaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(signedOperation)
        .setBaseFee(Transaction.MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val preparedTransaction = rpc.prepareTransaction(authorizedTransaction)
    preparedTransaction.sign(signer)

    val transactionResponse = rpc.sendTransaction(preparedTransaction)

    return transactionResponse.hash
  }

  private val submissionLock = Mutex()

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

fun String.inject(search: String, replace: String): String {
  return AbstractIntegrationTests.inject(this, search to replace)
}
