package org.stellar.anchor.platform.integrationtest

import com.google.common.io.BaseEncoding
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.spyk
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerClientHelper
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.sep10.Sep10Service
import org.stellar.anchor.util.FileUtil
import org.stellar.anchor.util.KeyUtil
import org.stellar.sdk.*
import org.stellar.sdk.Network.FUTURENET
import org.stellar.sdk.Network.TESTNET
import org.stellar.sdk.operations.ManageDataOperation
import org.stellar.sdk.operations.SetOptionsOperation

class Sep10ServiceIntegrationTests : AbstractIntegrationTests(TestConfig()) {
  companion object {
    const val TEST_WEB_AUTH_DOMAIN = "test.stellar.org"
    const val TEST_HOME_DOMAIN = "test.stellar.org"
    val testAccountWithNonCompliantSigner: String =
      FileUtil.getResourceFileAsString("test_account_with_noncompliant_signer.json")

    @JvmStatic
    fun ledgerClients(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(
          Horizon("https://horizon-testnet.stellar.org"),
          "https://horizon-testnet.stellar.org",
          TESTNET,
        ),
        Arguments.of(
          Horizon("https://horizon-futurenet.stellar.org"),
          "https://horizon-futurenet.stellar.org",
          FUTURENET,
        ),
        Arguments.of(
          StellarRpc("https://soroban-testnet.stellar.org"),
          "https://horizon-testnet.stellar.org",
          TESTNET,
        ),
      )
    }
  }

  @MockK(relaxed = true) lateinit var appConfig: AppConfig
  @MockK(relaxed = true) lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) lateinit var custodySecretConfig: CustodySecretConfig
  @MockK(relaxed = true) lateinit var sep10Config: Sep10Config
  @MockK(relaxed = true) lateinit var ledgerClient: LedgerClient
  @MockK(relaxed = true) lateinit var clientFinder: ClientFinder

  private lateinit var jwtService: JwtService
  private lateinit var sep10Service: Sep10Service
  private lateinit var httpClient: OkHttpClient

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { sep10Config.webAuthDomain } returns TEST_WEB_AUTH_DOMAIN
    every { sep10Config.authTimeout } returns 900
    every { sep10Config.jwtTimeout } returns 900
    every { sep10Config.homeDomains } returns listOf(TEST_HOME_DOMAIN, "*.wildcard.stellar.org")
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase

    secretConfig.setupMock()
    this.jwtService = spyk(JwtService(secretConfig, custodySecretConfig))
    this.sep10Service =
      Sep10Service(appConfig, secretConfig, sep10Config, ledgerClient, jwtService, clientFinder)
    this.httpClient = `create httpClient`()
  }

  fun SecretConfig.setupMock(block: (() -> Any)? = null) {
    val cfg = this

    every { cfg.sep6MoreInfoUrlJwtSecret } returns
      "jwt_secret_sep_6_more_info_url_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
    every { cfg.sep10JwtSecretKey } returns
      "jwt_secret_sep_10_secret_key_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
    every { cfg.sep10SigningSeed } returns
      config.get("secret.sep10.signing.seed").also { KeyUtil.validateJWTSecret(it) }
    every { cfg.sep24InteractiveUrlJwtSecret } returns
      "jwt_secret_sep_24_interactive_url_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
    every { cfg.sep24MoreInfoUrlJwtSecret } returns
      "jwt_secret_sep_24_more_info_url_jwt_secret".also { KeyUtil.validateJWTSecret(it) }
    every { cfg.callbackAuthSecret } returns
      "callback_auth_secret_key____________________________".also { KeyUtil.validateJWTSecret(it) }
    every { cfg.platformAuthSecret } returns
      "platform_auth_secret_key____________________________".also { KeyUtil.validateJWTSecret(it) }

    block?.invoke()
  }

  @ParameterizedTest
  @MethodSource("ledgerClients")
  fun `test challenge with non existent account and client domain`(ledgerClient: LedgerClient) {
    // 1 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = "test.stellar.org"
    val serverHomeDomain = "test.stellar.org"
    val serverKP = KeyPair.random()

    // clientDomainKP does not exist in the network. It refers to the wallet (like Lobstr's)
    // account.
    val clientDomainKP = KeyPair.random()

    // The public key of the client that DOES NOT EXIST.
    val clientKP = KeyPair.random()

    val nonce = ByteArray(48)
    val random = SecureRandom()
    random.nextBytes(nonce)
    val base64Encoding = BaseEncoding.base64()
    val encodedNonce = base64Encoding.encode(nonce).toByteArray()

    val sourceAccount = Account(serverKP.accountId, -1L)
    val op1DomainNameMandatory =
      ManageDataOperation.builder()
        .name("$serverHomeDomain auth")
        .value(encodedNonce)
        .sourceAccount(clientKP.accountId)
        .build()
    val op2WebAuthDomainMandatory =
      ManageDataOperation.builder()
        .name("web_auth_domain")
        .value(serverWebAuthDomain.toByteArray())
        .sourceAccount(serverKP.accountId)
        .build()
    val op3clientDomainOptional =
      ManageDataOperation.builder()
        .name("client_domain")
        .value("lobstr.co".toByteArray())
        .sourceAccount(clientDomainKP.accountId)
        .build()

    val transaction =
      TransactionBuilder(sourceAccount, TESTNET)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(100)
        .addOperation(op1DomainNameMandatory)
        .addOperation(op2WebAuthDomainMandatory)
        .addOperation(op3clientDomainOptional)
        .build()

    transaction.sign(serverKP)
    transaction.sign(clientDomainKP)
    transaction.sign(clientKP)

    // 2 ------ Create Services
    every { secretConfig.sep10SigningSeed } returns String(serverKP.secretSeed)
    this.sep10Service =
      Sep10Service(appConfig, secretConfig, sep10Config, ledgerClient, jwtService, clientFinder)

    // 3 ------ Run tests
    val validationRequest = ValidationRequest.of(transaction.toEnvelopeXdrBase64())
    assertDoesNotThrow { sep10Service.validateChallenge(validationRequest) }
  }

  @ParameterizedTest
  @MethodSource("ledgerClients")
  fun `test challenge with existent account multisig with invalid ed dsa public key and client domain`(
    ledgerClient: LedgerClient
  ) {
    // 1 ------ Mock client account and its response from horizon
    // The public key of the client that exists thanks to a mockk
    val clientKP = KeyPair.random()
    val mockHorizon = MockWebServer()
    mockHorizon.start()

    mockHorizon.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(testAccountWithNonCompliantSigner.replace("%ACCOUNT_ID%", clientKP.accountId))
    )
    val mockHorizonUrl = mockHorizon.url("").toString()

    // 2 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
    val serverKP = KeyPair.random()

    // clientDomainKP does not exist in the network. It refers to the wallet (like Lobstr's)
    // account.
    val clientDomainKP = KeyPair.random()

    val nonce = ByteArray(48)
    val random = SecureRandom()
    random.nextBytes(nonce)
    val base64Encoding = BaseEncoding.base64()
    val encodedNonce = base64Encoding.encode(nonce).toByteArray()

    val sourceAccount = Account(serverKP.accountId, -1L)
    val op1DomainNameMandatory =
      ManageDataOperation.builder()
        .name("$serverHomeDomain auth")
        .value(encodedNonce)
        .sourceAccount(clientKP.accountId)
        .build()
    val op2WebAuthDomainMandatory =
      ManageDataOperation.builder()
        .name("web_auth_domain")
        .value(serverWebAuthDomain.toByteArray())
        .sourceAccount(serverKP.accountId)
        .build()
    val op3clientDomainOptional =
      ManageDataOperation.builder()
        .name("client_domain")
        .value("lobstr.co".toByteArray())
        .sourceAccount(clientDomainKP.accountId)
        .build()

    val transaction =
      TransactionBuilder(sourceAccount, TESTNET)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(100)
        .addOperation(op1DomainNameMandatory)
        .addOperation(op2WebAuthDomainMandatory)
        .addOperation(op3clientDomainOptional)
        .build()

    transaction.sign(serverKP)
    transaction.sign(clientDomainKP)
    transaction.sign(clientKP)

    // 2 ------ Create Services
    every { secretConfig.sep10SigningSeed } returns String(serverKP.secretSeed)
    every { appConfig.horizonUrl } returns mockHorizonUrl
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase
    this.sep10Service =
      Sep10Service(appConfig, secretConfig, sep10Config, ledgerClient, jwtService, clientFinder)

    // 3 ------ Run tests
    val validationRequest = ValidationRequest.of(transaction.toEnvelopeXdrBase64())
    assertDoesNotThrow { sep10Service.validateChallenge(validationRequest) }
  }

  @ParameterizedTest
  @MethodSource("ledgerClients")
  fun `test the challenge with existent account, multisig, and client domain`(
    ledgerClient: LedgerClient,
    horizonUrl: String,
    network: Network,
  ) {
    // 1 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
    val serverKP = KeyPair.random()

    // clientDomainKP doesn't exist in the network. Refers to the walletAcc (like Lobstr's)
    val clientDomainKP = KeyPair.random()

    // Master account of the multisig. It'll be created in the network.
    val clientMasterKP = KeyPair.random()
    val clientAddress = clientMasterKP.accountId
    // Secondary account of the multisig. It'll be created in the network.
    val clientSecondaryKP = KeyPair.random()

    val nonce = ByteArray(48)
    val random = SecureRandom()
    random.nextBytes(nonce)
    val base64Encoding = BaseEncoding.base64()
    val encodedNonce = base64Encoding.encode(nonce).toByteArray()

    val sourceAccount = Account(serverKP.accountId, -1L)
    val op1DomainNameMandatory =
      ManageDataOperation.builder()
        .name("$serverHomeDomain auth")
        .value(encodedNonce)
        .sourceAccount(clientAddress)
        .build()
    val op2WebAuthDomainMandatory =
      ManageDataOperation.builder()
        .name("web_auth_domain")
        .value(serverWebAuthDomain.toByteArray())
        .sourceAccount(serverKP.accountId)
        .build()
    val op3clientDomainOptional =
      ManageDataOperation.builder()
        .name("client_domain")
        .value("lobstr.co".toByteArray())
        .sourceAccount(clientDomainKP.accountId)
        .build()

    val transaction =
      TransactionBuilder(sourceAccount, network)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(100)
        .addOperation(op1DomainNameMandatory)
        .addOperation(op2WebAuthDomainMandatory)
        .addOperation(op3clientDomainOptional)
        .build()

    transaction.sign(serverKP)
    transaction.sign(clientDomainKP)
    transaction.sign(clientMasterKP)
    transaction.sign(clientSecondaryKP)

    // 2 ------ Create Services
    every { secretConfig.sep10SigningSeed } returns String(serverKP.secretSeed)
    every { appConfig.horizonUrl } returns horizonUrl
    every { appConfig.stellarNetworkPassphrase } returns network.networkPassphrase

    this.sep10Service =
      Sep10Service(appConfig, secretConfig, sep10Config, ledgerClient, jwtService, clientFinder)

    // 3 ------ Setup multisig
    val httpRequest =
      Request.Builder()
        .url("$horizonUrl/friendbot?addr=" + clientMasterKP.accountId)
        .header("Content-Type", "application/json")
        .get()
        .build()
    val response = httpClient.newCall(httpRequest).execute()
    Assertions.assertEquals(200, response.code)

    val clientAccount = ledgerClient.getAccount(clientMasterKP.accountId)
    val multisigTx =
      TransactionBuilder(clientAccount, network)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(900)).build()
        )
        .setBaseFee(300)
        .addOperation(
          SetOptionsOperation.builder()
            .lowThreshold(20)
            .mediumThreshold(20)
            .highThreshold(20)
            .signer(Signer.ed25519PublicKey(clientSecondaryKP))
            .signerWeight(10)
            .masterKeyWeight(10)
            .build()
        )
        .build()
    multisigTx.sign(clientMasterKP)
    val txnResponse = ledgerClient.submitTransaction(multisigTx)
    LedgerClientHelper.waitForTransactionAvailable(ledgerClient, txnResponse.hash)

    // 4 ------ Run tests
    val validationRequest = ValidationRequest.of(transaction.toEnvelopeXdrBase64())
    assertDoesNotThrow { sep10Service.validateChallenge(validationRequest) }
  }

  fun `create httpClient`(): OkHttpClient {
    return OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .hostnameVerifier { _, _ -> true }
      .build()
  }
}
