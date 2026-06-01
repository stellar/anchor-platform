package org.stellar.anchor.platform.integrationtest

import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.walletsdk.asset.IssuedAssetId

/**
 * Verifies per-client callback isolation: when the event processor broadcasts a
 * TRANSACTION_STATUS_CHANGED event, only the ClientStatusCallbackHandler bound to the owning client
 * fires an HTTP request. A second callback-enabled client ("circleWallet") that does not own the
 * transaction must receive nothing.
 *
 * Requires the platform to be running with the clients.yaml that contains the "referenceCustodial"
 * custodial client (callback → wallet-server:8092) and the "circleWallet" noncustodial client
 * (callback → localhost:19091). Start via: ./gradlew startAllServers
 */
@TestInstance(PER_CLASS)
class MultiClientCallbackTests : IntegrationTestBase(TestConfig()) {
  private lateinit var circleWalletServer: MockWebServer

  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))
  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)

  @BeforeAll
  fun startCircleWalletServer() {
    circleWalletServer = MockWebServer()
    repeat(5) { circleWalletServer.enqueue(MockResponse().setResponseCode(200)) }
    circleWalletServer.start(CIRCLE_WALLET_PORT)
  }

  @AfterAll
  fun stopCircleWalletServer() {
    circleWalletServer.shutdown()
  }

  @Test
  fun `sep24 withdrawal callback is delivered only to the owning client and not to the circleWallet`() =
    runBlocking {
      walletServerClient.clearCallbacks()

      val withdrawRequest: HashMap<String, String> =
        GsonUtils.getInstance()
          .fromJson(
            WITHDRAW_REQUEST_JSON,
            object : TypeToken<HashMap<String, String>>() {}.type,
          )
      val response =
        anchor
          .sep24()
          .withdraw(
            IssuedAssetId("USDC", USDC_ISSUER),
            token,
            withdrawRequest,
            WITHDRAW_SOURCE_ACCOUNT,
          )
      val txnId = response.id

      platformApiClient.patchTransaction(buildPatchRequest(txnId))

      val callbacks =
        walletServerClient.pollTransactionCallbacks(
          "sep24",
          txnId,
          1,
          Sep24GetTransactionResponse::class.java,
        )
      assertThat(callbacks)
        .withFailMessage("referenceCustodial client should receive exactly one SEP-24 callback")
        .hasSize(1)

      assertThat(circleWalletServer.requestCount)
        .withFailMessage(
          "circleWallet callback receiver (localhost:$CIRCLE_WALLET_PORT) must not receive any requests"
        )
        .isEqualTo(0)
    }

  private fun buildPatchRequest(txnId: String): PatchTransactionsRequest =
    GsonUtils.getInstance()
      .fromJson(
        """
        {
          "records": [{
            "transaction": {
              "id": "$txnId",
              "status": "completed",
              "amount_in":  {"amount": "10", "asset": "stellar:USDC:$USDC_ISSUER"},
              "amount_out": {"amount": "10", "asset": "iso4217:USD"},
              "fee_details": {"total": "1",  "asset": "stellar:USDC:$USDC_ISSUER"},
              "message": "completed by MultiClientCallbackTests"
            }
          }]
        }
        """
          .trimIndent(),
        PatchTransactionsRequest::class.java,
      )

  companion object {
    private const val CIRCLE_WALLET_PORT = 19091
    private const val USDC_ISSUER = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    private const val WITHDRAW_SOURCE_ACCOUNT =
      "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4"

    private val WITHDRAW_REQUEST_JSON =
      """
      {
        "amount": "10",
        "asset_code": "USDC",
        "asset_issuer": "$USDC_ISSUER",
        "account": "$WITHDRAW_SOURCE_ACCOUNT",
        "lang": "en"
      }
      """
        .trimIndent()
  }
}
