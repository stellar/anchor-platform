package org.stellar.reference.service

import com.google.gson.reflect.TypeToken
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.math.BigDecimal
import java.util.*
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.apache.commons.codec.binary.Hex
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.data.*
import org.stellar.reference.data.Transaction
import org.stellar.sdk.*
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.TransactionResponse

class SepHelper(private val cfg: Config) {
  private val log = KotlinLogging.logger {}
  private val gson = GsonUtils.getInstance()

  val client = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
  }

  val baseUrl = cfg.appSettings.platformApiEndpoint

  val server = Server(cfg.appSettings.horizonEndpoint)

  internal suspend fun patchTransaction(patchRecord: PatchTransactionTransaction) {
    val resp =
      client.patch("$baseUrl/transactions") {
        contentType(ContentType.Application.Json)
        setBody(PatchTransactionsRequest(listOf(PatchTransactionRecord(patchRecord))))
      }

    if (resp.status != HttpStatusCode.OK) {
      val respBody = resp.bodyAsText()

      log.error { "Unexpected status code on patch transaction. Response body: $respBody" }

      throw Exception(respBody)
    }
  }

  internal suspend fun rpcAction(method: String, params: RpcActionParamsRequest) {
    val resp =
      client.post(baseUrl) {
        contentType(ContentType.Application.Json)
        setBody(listOf(RpcRequest(UUID.randomUUID().toString(), "2.0", method, params)))
      }

    val respBody = resp.bodyAsText()

    val rpcResponseType = object : TypeToken<List<RpcResponse>>() {}.type
    if (
      resp.status != HttpStatusCode.OK ||
        gson.fromJson<List<RpcResponse>>(respBody, rpcResponseType)[0].error != null
    ) {
      log.error { "Unexpected error on rpc request. Response body: $respBody" }
      throw Exception(respBody)
    }
  }

  internal suspend fun patchTransaction(
    transactionId: String,
    newStatus: String,
    message: String? = null,
  ) {
    patchTransaction(PatchTransactionTransaction(transactionId, newStatus, message))
  }

  internal suspend fun getTransaction(transactionId: String): Transaction {
    return client.get("$baseUrl/transactions/$transactionId").body()
  }

  internal fun sendStellarTransaction(
    destinationAddress: String,
    assetString: String,
    amount: BigDecimal,
    memo: String?,
    memoType: String?,
  ): String {
    val keypair = KeyPair.fromSecretSeed(cfg.appSettings.paymentSigningSeed)
    val myAccount = server.accounts().account(keypair.accountId)
    val asset = Asset.create(assetString.replace("stellar:", ""))

    val transactionBuilder =
      TransactionBuilder(myAccount, Network.TESTNET)
        .setBaseFee(100)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(60)).build()
        )
        .addOperation(
          PaymentOperation.builder()
            .destination(destinationAddress)
            .asset(asset)
            .amount(amount)
            .build()
        )

    if (memo != null && memoType != null) {
      transactionBuilder.addMemo(
        when (memoType) {
          "text" -> Memo.text(memo)
          "id" -> Memo.id(memo.toLong())
          "hash" -> Memo.hash(Hex.encodeHexString(Base64.getDecoder().decode(memo)))
          else -> throw Exception("Unsupported memo type")
        }
      )
    }

    val transaction = transactionBuilder.build()

    transaction.sign(keypair)

    val resp: TransactionResponse
    try {
      resp = server.submitTransaction(transaction)
    } catch (e: BadRequestException) {
      throw Exception("Failed to submit transaction with code: ${e.problem?.extras?.resultCodes}")
    }
    assert(resp.successful)
    return resp.hash
  }

  internal suspend fun sendCustodyStellarTransaction(transactionId: String) {
    val resp =
      client.post("$baseUrl/transactions/$transactionId/payments") {
        contentType(ContentType.Application.Json)
        setBody("{}")
      }

    if (resp.status != HttpStatusCode.OK) {
      val respBody = resp.bodyAsText()

      log.error {
        "Unexpected status code when sending custody Stellar transaction. Response body: $respBody"
      }

      throw Exception(respBody)
    }
  }

  // Pulling status change from anchor. Alternatively, listen to AnchorEvent for transaction status
  // change
  internal suspend fun waitStellarTransaction(txId: String, status: String) {
    for (i in 1..(30 * 60 / 5)) {
      log.info { "Waiting for funds transfer" }

      val transaction = getTransaction(txId)

      if (transaction.status == status) {
        log.info { "Funds transfer was successful" }

        return
      } else {
        delay(5.seconds)
      }
    }

    throw Exception("Transaction hasn't been sent in 30 minutes, giving up")
  }

  internal fun validateTransaction(transaction: Transaction) {
    // Sum of payments (if multiple) sent by the user, filtered by requested asset
    val paymentSum =
      transaction.stellarTransactions!!
        .flatMap { it.payments }
        .filter { "stellar:${it.amount.asset}" == transaction.amountIn!!.asset }
        .map { it.amount.amount!!.toBigDecimal() }
        .reduce(BigDecimal::add)

    val amountIn = transaction.amountIn!!.amount!!.toBigDecimal()

    if (paymentSum < amountIn) {
      throw Exception(
        "Amount of payments received is not equal to amount requested. Deficit: ${
                    (paymentSum - amountIn).stripTrailingZeros().toPlainString()
                })"
      )
    }
  }
}
