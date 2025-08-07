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
import org.stellar.anchor.config.RpcAuthConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.config.StellarNetworkConfig.ProviderType.HORIZON
import org.stellar.anchor.config.StellarNetworkConfig.ProviderType.RPC
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerClientHelper
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.config.PropertyStellarNetworkConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.*
import org.stellar.sdk.Network.TESTNET
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.AccountResponse
import org.stellar.sdk.xdr.AssetType
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.TransactionEnvelope

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class LedgerClientTests : IntegrationTestBase(TestConfig()) {
  private val stellarNetworkConfig = mockk<StellarNetworkConfig>()
  private val trustlineTestAccount = "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
  val gson = GsonUtils.getInstance()!!
  private lateinit var sourceKeypair: KeyPair
  private lateinit var destKeyPair: KeyPair

  @BeforeAll
  fun setup() {
    every { stellarNetworkConfig.type } returns HORIZON
    every { stellarNetworkConfig.rpcUrl } returns "https://soroban-testnet.stellar.org"
    every { stellarNetworkConfig.horizonUrl } returns "https://horizon-testnet.stellar.org"
    every { stellarNetworkConfig.network } returns "TESTNET"
    every { stellarNetworkConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase

    sourceKeypair = KeyPair.random()
    destKeyPair = KeyPair.fromAccountId("GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG")
    prepareAccount(Server(stellarNetworkConfig.horizonUrl), sourceKeypair)
  }

  @ParameterizedTest
  @MethodSource("getLedgerClient")
  fun `test getAccount()`(ledgerClient: LedgerClient) {
    val account = ledgerClient.getAccount(trustlineTestAccount)
    JSONAssert.assertEquals(expectedAccount, gson.toJson(account), JSONCompareMode.LENIENT)
  }

  @ParameterizedTest
  @MethodSource("getLedgerClient")
  fun `test getTrustline()`(ledgerClient: LedgerClient) {
    assertTrue(
      ledgerClient.hasTrustline(
        trustlineTestAccount,
        "USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    )

    assertFalse(
      ledgerClient.hasTrustline(
        trustlineTestAccount,
        "JPY:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    )
  }

  @ParameterizedTest
  @MethodSource("getLedgerClient")
  fun `test submitTransaction() then getTransaction()`(ledgerClient: LedgerClient) {
    val (sourceKp, _, paymentTxn) = buildPaymentTransaction(ledgerClient)
    val txn =
      LedgerClientHelper.waitForTransactionAvailable(
        ledgerClient,
        ledgerClient.submitTransaction(paymentTxn).hash,
      )

    assertEquals(sourceKp.accountId, txn.sourceAccount)
    assertEquals("Hello Stellar!", txn.memo.text.toString())

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

  private fun buildPaymentTransaction(
    ledgerClient: LedgerClient
  ): Triple<KeyPair, KeyPair, Transaction> {
    val sourceAccount = ledgerClient.getAccount(sourceKeypair.accountId)

    val paymentOperation =
      PaymentOperation.builder()
        .destination(destKeyPair.accountId)
        .asset(Asset.createNativeAsset())
        .amount(BigDecimal("0.000123"))
        .build()

    val transaction =
      TransactionBuilder(Account(sourceKeypair.accountId, sourceAccount.sequenceNumber), TESTNET)
        .setBaseFee(AbstractTransaction.MIN_BASE_FEE)
        .addMemo(Memo.text("Hello Stellar!"))
        .setTimeout(30)
        .addOperation(paymentOperation)
        .build()
    transaction.sign(sourceKeypair)
    return Triple(sourceKeypair, destKeyPair, transaction)
  }

  // The warning is suppressed because this method is used to provide the LedgerClient instances for
  // the parameterized tests.
  @SuppressWarnings("unused")
  private fun getLedgerClient(): List<Array<Any>> {
    val stellarRpc = StellarRpc(stellarNetworkConfig.rpcUrl)
    val horizon = Horizon(stellarNetworkConfig)
    val quickNodeRpc = createQuickNodeRpc()
    val gatewayRpc = createGatewayRpc()

    return listOf(arrayOf(stellarRpc), arrayOf(horizon), arrayOf(quickNodeRpc), arrayOf(gatewayRpc))
  }

  private fun createGatewayRpc(): StellarRpc {
    val secretConfig = mockk<SecretConfig>()
    every { secretConfig.rpcAuthSecret } returns this.config.env["gateway.api.key"]
    val config =
      PropertyStellarNetworkConfig().apply {
        network = "TESTNET"
        type = RPC
        rpcUrl = "https://rpc.eu-central-8.gateway.fm/v4/soroban/non-archival/testnet"
        rpcAuth =
          RpcAuthConfig().apply {
            type = RpcAuthConfig.RpcAuthType.BEARER_TOKEN

            bearerToken =
              RpcAuthConfig.BearerTokenConfig().apply {
                header = "Authorization"
                prefix = "Bearer"
              }
          }
      }

    return StellarRpc(config, secretConfig)
  }

  private fun createQuickNodeRpc(): StellarRpc {
    val secretConfig = mockk<SecretConfig>()
    every { secretConfig.rpcAuthSecret } returns this.config.env["quicknode.api.key"]
    val config =
      PropertyStellarNetworkConfig().apply {
        network = "TESTNET"
        type = RPC
        rpcUrl = "https://bitter-few-friday.stellar-testnet.quiknode.pro"
        rpcAuth =
          RpcAuthConfig().apply {
            type = RpcAuthConfig.RpcAuthType.URL
            url =
              RpcAuthConfig.UrlConfig().apply { type = RpcAuthConfig.UrlConfig.UrlType.PATH_APPEND }
          }
      }
    return StellarRpc(config, secretConfig)
  }

  fun prepareAccount(horizonServer: Server, kp: KeyPair): AccountResponse? {
    val friendBotUrl = "https://friendbot.stellar.org/?addr=${kp.accountId}"
    try {
      java.net.URL(friendBotUrl).openStream()
      val account = horizonServer.accounts().account(kp.accountId)
      return account
    } catch (e: java.io.IOException) {
      throw e
    }
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
