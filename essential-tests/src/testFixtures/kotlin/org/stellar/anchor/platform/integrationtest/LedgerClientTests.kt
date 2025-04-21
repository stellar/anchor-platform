package org.stellar.anchor.platform.integrationtest

import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.*
import org.stellar.sdk.Network.TESTNET
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.xdr.AssetType
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.TransactionEnvelope

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class LedgerClientTests {
  private val appConfig = mockk<AppConfig>()
  val gson = GsonUtils.getInstance()!!

  @BeforeAll
  fun setup() {
    every { appConfig.rpcUrl } returns "https://soroban-testnet.stellar.org"
    every { appConfig.horizonUrl } returns "https://horizon-testnet.stellar.org"
    every { appConfig.stellarNetwork } returns "TESTNET"
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase
  }

  @ParameterizedTest
  @MethodSource("getLedgerClient")
  fun `test getAccount()`(ledgerClient: LedgerClient, accountId: String) {
    val account = ledgerClient.getAccount(accountId)
    JSONAssert.assertEquals(expectedAccount, gson.toJson(account), JSONCompareMode.LENIENT)
  }

  @ParameterizedTest
  @MethodSource("getLedgerClient")
  fun `test getTrustline()`(ledgerClient: LedgerClient, accountId: String) {
    assertTrue(
      ledgerClient.hasTrustline(
        accountId,
        "USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    )

    assertFalse(
      ledgerClient.hasTrustline(
        accountId,
        "JPY:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    )
  }

  @ParameterizedTest
  @MethodSource("getLedgerClient")
  fun `test submitTransaction() then getTransaction()`(
    ledgerClient: LedgerClient,
    accountId: String,
  ) {
    val paymentTxn = buildPaymentTransaction(ledgerClient)
    val result = ledgerClient.submitTransaction(paymentTxn)

    var txn: LedgerTransaction? = null
    for (i in 1..10) {
      try {
        txn = ledgerClient.getTransaction(result.hash)
        break
      } catch (e: Exception) {
        println(e.message)
        // wait and retry
        Thread.sleep(1000)
      }
    }

    JSONAssert.assertEquals(expectedTxn, gson.toJson(txn), JSONCompareMode.LENIENT)

    TransactionEnvelope.fromXdrBase64(txn!!.envelopeXdr).let {
      assertNotNull(it.v1)
      assertNotNull(it.v1.tx)
      assertNotNull(it.v1.tx.operations)
      val op = it.v1.tx.operations[0]
      assertNotNull(op)
      assertNotNull(op.body.paymentOp)
      assertEquals(op.body.discriminant, OperationType.PAYMENT)
      assertNotNull(op.body.paymentOp)
      assertEquals(op.body.paymentOp.asset.discriminant, AssetType.ASSET_TYPE_NATIVE)
      assertEquals(op.body.paymentOp.amount.int64, 1230)
    }
  }

  private fun buildPaymentTransaction(ledgerClient: LedgerClient): Transaction? {
    val sourceKeypair =
      KeyPair.fromSecretSeed("SAJW2O2NH5QMMVWYAN352OEXS2RUY675A2HPK5HEG2FRR2NXPYA4OLYN")
    // The destination account is the account we will be sending the lumens to.
    val destination =
      KeyPair.fromSecretSeed("SBHTWEF5U7FK53FLGDMBQYGXRUJ24VBM3M6VDXCHRIGCRG3Z64PH45LW").accountId

    val sourceAccount = ledgerClient.getAccount(sourceKeypair.accountId)

    val paymentOperation =
      PaymentOperation.builder()
        .destination(destination)
        .asset(Asset.createNativeAsset())
        .amount(BigDecimal("0.000123"))
        .build()

    val transaction =
      TransactionBuilder(
          Account(sourceKeypair.accountId, sourceAccount.sequenceNumber),
          TESTNET,
        ) // we are going to submit the transaction to the test network
        .setBaseFee(AbstractTransaction.MIN_BASE_FEE) // set base fee, see
        .addMemo(Memo.text("Hello Stellar!")) // Add a text memo
        .setTimeout(30) // Make this transaction valid for the next 30 seconds only
        .addOperation(paymentOperation) // Add the payment operation to the transaction
        .build()
    transaction.sign(sourceKeypair)
    return transaction
  }

  private fun getLedgerClient(): List<Array<Any>> {
    val stellarRpc = StellarRpc(appConfig)
    val horizon = Horizon(appConfig)

    return listOf(
      arrayOf(stellarRpc, "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"),
      arrayOf(horizon, "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"),
    )
  }
}

private val expectedAccount =
  """
{
  "accountId": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
  "thresholds": {
    "low": 0,
    "medium": 0,
    "high": 0
  },
  "signers": [
    {
      "key": "GATEYCIMJZ2F6Y437QSYH4XFQ6HLD5YP4MBJZFFPZVEQDJOY4QTCB7BB",
      "type": "SIGNER_KEY_TYPE_ED25519",
      "weight": 1
    },
    {
      "key": "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O",
      "type": "SIGNER_KEY_TYPE_ED25519",
      "weight": 1
    },
    {
      "key": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "type": "SIGNER_KEY_TYPE_ED25519",
      "weight": 1
    }
  ]
}  
"""
    .trimIndent()

private val expectedTxn =
  """
  {
    "sourceAccount": "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
    "memo": {
      "text": {
        "bytes": "SGVsbG8gU3RlbGxhciE="
      }
    }
  }
"""
    .trimIndent()
