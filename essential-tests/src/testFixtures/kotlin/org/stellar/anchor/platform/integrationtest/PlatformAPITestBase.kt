package org.stellar.anchor.platform.integrationtest

import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerClientHelper.waitForTransactionAvailable
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.AssetHelper
import org.stellar.anchor.util.AssetHelper.fromXdrAmount
import org.stellar.anchor.util.Log.info
import org.stellar.anchor.util.MemoHelper
import org.stellar.anchor.util.StringHelper.isNotEmpty
import org.stellar.sdk.*
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.TransactionEnvelope

open class PlatformAPITestBase(config: TestConfig) : IntegrationTestBase(config) {
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

    private val paymentValuesByClass = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val classLocks = ConcurrentHashMap<String, Any>()
  }

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
    val key = this::class.java.simpleName
    paymentValuesByClass[key]?.let {
      return it
    }
    val lock = classLocks.getOrPut(key) { Any() }
    synchronized(lock) {
      paymentValuesByClass[key]?.let {
        return it
      }
      if (isNotEmpty(config.get("stellar_network.rpc_url"))) {
        val ledgerClient = StellarRpc(config.get("stellar_network.rpc_url")!!)
        val ledgerTxn = sendTestPayment(ledgerClient)
        setTestPaymentsValues(key, ledgerTxn!!)
      } else if (isNotEmpty(config.get("stellar_network.horizon_url"))) {
        val horizonServer = Server(config.get("stellar_network.horizon_url")!!)
        val payment = fetchTestPaymentFromHorizon(horizonServer)
        val ledgerTxn: LedgerTransaction? =
          if (payment != null) {
            toLedgerTransaction(payment)
          } else {
            val ledgerClient = Horizon(config.get("stellar_network.horizon_url")!!)
            sendTestPayment(ledgerClient)
          }
        setTestPaymentsValues(key, ledgerTxn!!)
      } else {
        throw Exception("None of stellar_network.rpc_url or stellar_network.horizon_url is not set")
      }
      return paymentValuesByClass[key]!!
    }
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

  private fun toLedgerOperation(op: OperationResponse): LedgerOperation? {
    val builder = LedgerOperation.builder()
    // TODO: Capture muxed account events
    when (op) {
      is PaymentOperationResponse -> {
        builder.type(OperationType.PAYMENT)
        builder.paymentOperation(
          LedgerTransaction.LedgerPaymentOperation.builder()
            .id(op.getId().toString())
            .from(op.from)
            .to(op.to)
            .amount(AssetHelper.toXdrAmount(op.amount).toBigInteger())
            .asset(op.asset.toXdr())
            .sourceAccount(op.getSourceAccount())
            .build()
        )
      }
      is PathPaymentBaseOperationResponse -> {
        builder.type(OperationType.PATH_PAYMENT_STRICT_RECEIVE)
        builder.pathPaymentOperation(
          LedgerTransaction.LedgerPathPaymentOperation.builder()
            .type(OperationType.PATH_PAYMENT_STRICT_RECEIVE)
            .id(op.getId().toString())
            .from(op.from)
            .to(op.to)
            .amount(AssetHelper.toXdrAmount(op.amount).toBigInteger())
            .asset(op.asset.toXdr())
            .sourceAccount(op.getSourceAccount())
            .build()
        )
      }
      else -> {
        return null
      }
    }
    return builder.build()
  }

  /** The shared payment of the class: the fixed destination and memo, in Circle's testnet USDC. */
  private fun sendTestPayment(ledgerClient: LedgerClient): LedgerTransaction? {
    val usdcAsset =
      Asset.create(null, "USDC", "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5")
        as AssetTypeCreditAlphaNum
    return sendPayment(
      ledgerClient,
      TEST_PAYMENT_DEST_ACCOUNT,
      Memo.text(TEST_PAYMENT_MEMO),
      usdcAsset
    )
  }

  /** The ledger client of the configured network: Stellar RPC when set, otherwise Horizon. */
  protected fun testLedgerClient(): LedgerClient =
    if (isNotEmpty(config.get("stellar_network.rpc_url"))) {
      StellarRpc(config.get("stellar_network.rpc_url")!!)
    } else if (isNotEmpty(config.get("stellar_network.horizon_url"))) {
      Horizon(config.get("stellar_network.horizon_url")!!)
    } else {
      throw Exception("None of stellar_network.rpc_url or stellar_network.horizon_url is not set")
    }

  /**
   * Sends a payment of 0.0002 of [asset] from the payment signing account to [destination], with
   * [memo] when given, and returns the confirmed ledger transaction.
   */
  protected fun sendPayment(
    ledgerClient: LedgerClient,
    destination: String,
    memo: Memo?,
    asset: Asset,
  ): LedgerTransaction {
    val sourceKey = KeyPair.fromSecretSeed(config.get("app..payment.signing.seed"))
    val accountId = sourceKey.accountId

    for (attempt in 1..5) {
      info("sendTestPayment attempt $attempt/5 (class=${this::class.java.simpleName})")
      val account = ledgerClient.getAccount(accountId)
      val builder =
        TransactionBuilder(Account(accountId, account.sequenceNumber), Network.TESTNET)
          .addOperation(
            PaymentOperation.builder()
              .sourceAccount(sourceKey.accountId)
              .destination(destination)
              .asset(asset)
              .amount(BigDecimal("0.0002"))
              .build()
          )
      if (memo != null) builder.addMemo(memo)
      val txn =
        builder
          .addPreconditions(
            TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(180)).build()
          )
          .setBaseFee(Transaction.MIN_BASE_FEE)
          .build()
      txn.sign(sourceKey)

      val response = ledgerClient.submitTransaction(txn)
      val accepted =
        response.status == SendTransactionStatus.PENDING ||
          response.status == SendTransactionStatus.DUPLICATE
      if (accepted) {
        try {
          return waitForTransactionAvailable(ledgerClient, response.hash, 60, 60)
        } catch (e: Exception) {
          info(
            "sendTestPayment attempt $attempt: transaction not confirmed within timeout." +
              " Retrying in 3s..."
          )
        }
      } else {
        info(
          "sendTestPayment attempt $attempt rejected (status=${response.status}). Retrying in 3s..."
        )
      }
      Thread.sleep(3000)
    }
    throw Exception(
      "sendTestPayment failed after 5 attempts (class=${this::class.java.simpleName})"
    )
  }

  private fun setTestPaymentsValues(key: String, ledgerTxn: LedgerTransaction) {
    testPaymentValuesOf(ledgerTxn)?.let { paymentValuesByClass[key] = it }
  }

  /** The `%TESTPAYMENT_*%` values of a ledger transaction holding one payment operation. */
  protected fun testPaymentValuesOf(ledgerTxn: LedgerTransaction): List<Pair<String, String>>? {
    val txnEnv = TransactionEnvelope.fromXdrBase64(ledgerTxn.envelopeXdr)
    val paymentOp = txnEnv.v1.tx.operations[0].body.paymentOp ?: return null
    return listOf(
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
      Pair("%TESTPAYMENT_ASSET_CIRCLE_USDC%", AssetHelper.getSep11AssetName(paymentOp.asset)),
    )
  }

  /**
   * Like [inject], with the payment values of one transaction's own payment in place of the shared
   * payment of the class.
   */
  fun injectWith(
    target: String,
    paymentValues: List<Pair<String, String>>,
    vararg replacements: Pair<String, String>,
  ): String {
    var result = target
    for ((search, replace) in replacements) {
      result = result.replace(search, replace)
    }
    for ((search, replace) in paymentValues) {
      result = result.replace(search, replace)
    }
    return result.trimIndent()
  }

  /**
   * Pays what the transaction waits for, so the RPC can record the payment: a withdrawal or SEP-31
   * transaction gets its amount-in asset at its destination account with its memo; a deposit gets
   * its amount-out asset at its recipient (a payout does not have to carry the deposit's memo).
   */
  protected fun sendPaymentFor(txn: GetTransactionResponse): LedgerTransaction {
    val isDeposit = txn.kind == Kind.DEPOSIT || txn.kind == Kind.DEPOSIT_EXCHANGE
    val assetId = (if (isDeposit) txn.amountOut else txn.amountIn).asset
    val memo = if (isDeposit) null else MemoHelper.makeMemo(txn.memo, txn.memoType)
    return sendPayment(
      testLedgerClient(),
      txn.destinationAccount,
      memo,
      Asset.create(assetId.removePrefix("stellar:")),
    )
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
}
