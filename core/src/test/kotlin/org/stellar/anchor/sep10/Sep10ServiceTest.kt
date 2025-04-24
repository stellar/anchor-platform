@file:Suppress("unused")

package org.stellar.anchor.sep10

import com.google.common.io.BaseEncoding
import com.google.gson.annotations.SerializedName
import io.jsonwebtoken.Jwts
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_TOML
import org.stellar.anchor.TestConstants.Companion.TEST_HOME_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_HOME_DOMAIN_PATTERN
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.TestConstants.Companion.TEST_SIGNING_SEED
import org.stellar.anchor.TestConstants.Companion.TEST_WEB_AUTH_DOMAIN
import org.stellar.anchor.api.exception.*
import org.stellar.anchor.api.sep.sep10.ChallengeRequest
import org.stellar.anchor.api.sep.sep10.ChallengeResponse
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerClientHelper
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.setupMock
import org.stellar.anchor.util.FileUtil
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.NetUtil
import org.stellar.sdk.*
import org.stellar.sdk.Network.*
import org.stellar.sdk.exception.InvalidSep10ChallengeException
import org.stellar.sdk.operations.ManageDataOperation
import org.stellar.sdk.operations.SetOptionsOperation
import org.stellar.walletsdk.auth.DefaultAuthHeaderSigner
import org.stellar.walletsdk.auth.createAuthSignToken
import org.stellar.walletsdk.horizon.AccountKeyPair
import org.stellar.walletsdk.horizon.SigningKeyPair
import org.stellar.walletsdk.util.toJava

@Suppress("unused")
internal class TestSigner(
  @SerializedName("key") val key: String,
  @SerializedName("type") val type: String,
  @SerializedName("weight") val weight: Int,
  @SerializedName("sponsor") val sponsor: String,
) {
  fun toSigner(): LedgerClient.Signer {
    val gson = GsonUtils.getInstance()
    val json = gson.toJson(this)
    return gson.fromJson(json, LedgerClient.Signer::class.java)
  }
}

fun `create httpClient`(): OkHttpClient {
  return OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.MINUTES)
    .readTimeout(10, TimeUnit.MINUTES)
    .writeTimeout(10, TimeUnit.MINUTES)
    .hostnameVerifier { _, _ -> true }
    .build()
}

@ExtendWith(LockAndMockTest::class)
internal class Sep10ServiceTest {
  companion object {
    @JvmStatic
    fun homeDomains(): Stream<String> {
      return Stream.of(null, TEST_HOME_DOMAIN)
    }

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

    val testAccountWithNonCompliantSigner: String =
      FileUtil.getResourceFileAsString("test_account_with_noncompliant_signer.json")
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
  private val clientKeyPair: KeyPair = KeyPair.random()
  private val clientDomainKeyPair: KeyPair = KeyPair.random()

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { sep10Config.webAuthDomain } returns TEST_WEB_AUTH_DOMAIN
    every { sep10Config.authTimeout } returns 900
    every { sep10Config.jwtTimeout } returns 900
    every { sep10Config.homeDomains } returns listOf(TEST_HOME_DOMAIN, TEST_HOME_DOMAIN_PATTERN)

    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase

    secretConfig.setupMock()

    this.jwtService = spyk(JwtService(secretConfig, custodySecretConfig))
    this.sep10Service =
      Sep10Service(appConfig, secretConfig, sep10Config, ledgerClient, jwtService, clientFinder)
    this.httpClient = `create httpClient`()
  }

  @Synchronized
  fun createTestChallenge(
    clientDomain: String,
    homeDomain: String,
    signWithClientDomain: Boolean,
  ): String {
    val now = System.currentTimeMillis() / 1000L
    val signer = KeyPair.fromSecretSeed(TEST_SIGNING_SEED)
    val memo = MemoId(TEST_MEMO.toLong())
    val txn =
      Sep10ChallengeWrapper.instance()
        .newChallenge(
          signer,
          Network(TESTNET.networkPassphrase),
          clientKeyPair.accountId,
          homeDomain,
          TEST_WEB_AUTH_DOMAIN,
          TimeBounds(now, now + 900),
          clientDomain,
          if (clientDomain.isEmpty()) "" else clientDomainKeyPair.accountId,
          memo,
        )
    txn.sign(clientKeyPair)
    if (clientDomain.isNotEmpty() && signWithClientDomain) {
      txn.sign(clientDomainKeyPair)
    }
    return txn.toEnvelopeXdrBase64()
  }

  @ParameterizedTest
  @MethodSource("ledgerClients")
  fun `test challenge with non existent account and client domain`(ledgerClient: LedgerClient) {
    // 1 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
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
    // GDFWZYGUNUFW4H3PP3DSNGTDFBUHO6NUFPQ6FAPMCKEJ6EHDKX2CV2IM
    val clientKP =
      KeyPair.fromSecretSeed("SAUNXQPM7VDH3WMDRHJ2WIN27KD23XD4AZPE62V76Q2SJPXR3DQWEOPX")
    val mockHorizon = MockWebServer()
    mockHorizon.start()

    mockHorizon.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(testAccountWithNonCompliantSigner)
    )
    val mockHorizonUrl = mockHorizon.url("").toString()

    // 2 ------ Create Test Transaction

    // serverKP does not exist in the network.
    val serverWebAuthDomain = TEST_WEB_AUTH_DOMAIN
    val serverHomeDomain = TEST_HOME_DOMAIN
    // GDFWZYGUNUFW4H3PP3DSNGTDFBUHO6NUFPQ6FAPMCKEJ6EHDKX2CV2IM
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
  @CsvSource(value = ["true,test.client.stellar.org", "false,test.client.stellar.org", "false,"])
  @LockAndMockStatic([NetUtil::class, Sep10Challenge::class])
  fun `test create challenge ok`(clientAttributionRequired: Boolean, clientDomain: String?) {
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML

    every { sep10Config.isClientAttributionRequired } returns clientAttributionRequired
    every { sep10Config.allowedClientDomains } returns listOf(TEST_CLIENT_DOMAIN)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.clientDomain = clientDomain

    val challengeResponse = sep10Service.createChallenge(cr)

    assertEquals(challengeResponse.networkPassphrase, TESTNET.networkPassphrase)
    // TODO: This should be at most once but there is a concurrency bug in the test.
    verify(atLeast = 1, atMost = 2) {
      Sep10Challenge.newChallenge(
        any(),
        Network(TESTNET.networkPassphrase),
        TEST_ACCOUNT,
        TEST_HOME_DOMAIN,
        TEST_WEB_AUTH_DOMAIN,
        any(),
        clientDomain ?: "",
        any(),
        any(),
      )
    }
  }

  @Test
  fun `test validate challenge when client account is on Stellar network`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    val mockSigners =
      listOf(TestSigner(clientKeyPair.accountId, "SIGNER_KEY_TYPE_ED25519", 1, "").toSigner())
    val accountResponse =
      mockk<LedgerClient.Account> {
        every { accountId } returns clientKeyPair.accountId
        every { sequenceNumber } returns 1
        every { signers } returns mockSigners
        every { thresholds.medium } returns 1
      }

    every { ledgerClient.getAccount(any()) } returns accountResponse

    val response = sep10Service.validateChallenge(vr)
    val jwt = jwtService.decode(response.token, Sep10Jwt::class.java)
    assertEquals("${clientKeyPair.accountId}:$TEST_MEMO", jwt.sub)
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test validate challenge with client domain`() {
    val mockSigners =
      listOf(
        TestSigner(clientKeyPair.accountId, "SIGNER_KEY_TYPE_ED25519", 1, "").toSigner(),
        TestSigner(clientDomainKeyPair.accountId, "SIGNER_KEY_TYPE_ED25519", 1, "").toSigner(),
      )

    val accountResponse =
      mockk<LedgerClient.Account> {
        every { accountId } returns clientKeyPair.accountId
        every { sequenceNumber } returns 1
        every { signers } returns mockSigners
        every { thresholds.medium } returns 1
      }

    every { ledgerClient.getAccount(any()) } returns accountResponse

    val vr = ValidationRequest()
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, true)

    val validationResponse = sep10Service.validateChallenge(vr)

    val token = jwtService.decode(validationResponse.token, Sep10Jwt::class.java)
    assertEquals(token.clientDomain, TEST_CLIENT_DOMAIN)
    assertEquals(token.homeDomain, TEST_HOME_DOMAIN)

    // Test when the transaction was not signed by the client domain and the client account exists
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, false)
    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }

    // Test when the transaction was not signed by the client domain and the client account not
    // exists
    every { ledgerClient.getAccount(any()) } answers { throw LedgerException("mock error") }
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, false)

    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge when client account is not on network`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw LedgerException("mock error")
      }

    sep10Service.validateChallenge(vr)
  }

  @Suppress("CAST_NEVER_SUCCEEDS")
  @Test
  fun `Test validate challenge with bad request`() {
    assertThrows<SepValidationException> {
      sep10Service.validateChallenge(null as? ValidationRequest)
    }

    val vr = ValidationRequest()
    vr.transaction = null
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `Test validate challenge with bad home domain failure`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", "abc.badPattern.stellar.org", false)
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `Test request to create challenge with bad home domain failure`() {
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.homeDomain = "bad.homedomain.com"

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `Test create challenge with wildcard matched home domain success`() {
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(null)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.homeDomain = "abc.def.wildcard.stellar.org"

    sep10Service.createChallenge(cr)
  }

  @Test
  @LockAndMockStatic([NetUtil::class, Sep10Challenge::class])
  fun `Test create challenge request with empty memo`() {
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(null)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()

    sep10Service.createChallenge(cr)
  }

  @Test
  fun `test when account is custodial, but the client domain is specified, exception should be thrown`() {
    every { sep10Config.knownCustodialAccountList } returns listOf(TEST_ACCOUNT)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(null)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @ParameterizedTest
  @MethodSource("homeDomains")
  fun `test client domain failures`(homeDomain: String?) {
    every { sep10Config.isClientAttributionRequired } returns true
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.homeDomain = homeDomain
    cr.clientDomain = null

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }

    // Test client domain rejection
    cr.clientDomain = TEST_CLIENT_DOMAIN
    assertThrows<SepNotAuthorizedException> { sep10Service.createChallenge(cr) }
  }

  @Test
  fun `test createChallenge() with bad account`() {
    every { sep10Config.isClientAttributionRequired } returns false
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.account = "GXXX"

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["ABC", "12AB", "-1", "0", Integer.MIN_VALUE.toString()])
  fun `test createChallenge() with bad memo`(badMemo: String) {
    every { sep10Config.isClientAttributionRequired } returns false
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.account = TEST_ACCOUNT
    cr.memo = badMemo

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `Test fetch signing key`() {
    // Given
    sep10Service = spyk(sep10Service)
    every { sep10Service.fetchSigningKeyFromClientDomain(any()) } returns clientKeyPair.accountId
    // When
    var cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(null)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()

    sep10Service.createChallenge(cr)

    // Then
    verify(exactly = 1) { sep10Service.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN) }
    // Given
    every { sep10Service.fetchSigningKeyFromClientDomain(any()) } throws IOException("mock error")
    // When
    cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(null)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()

    val ioex = assertThrows<IOException> { sep10Service.createChallenge(cr) }
    // Then
    assertEquals(ioex.message, "mock error")
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test createChallengeResponse()`() {
    // Given
    sep10Service = spyk(sep10Service)
    // Given
    every { sep10Service.newChallenge(any(), any(), any()) } throws
      InvalidSep10ChallengeException("mock error")
    // When
    val sepex =
      assertThrows<SepException> {
        sep10Service.createChallengeResponse(
          ChallengeRequest.builder()
            .account(TEST_ACCOUNT)
            .memo(TEST_MEMO)
            .homeDomain(TEST_HOME_DOMAIN)
            .clientDomain(TEST_CLIENT_DOMAIN)
            .build(),
          MemoId(1234567890),
          null,
        )
      }
    // Then
    assertTrue(sepex.message!!.startsWith("Failed to create the sep-10 challenge"))
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test getClientAccountId failure`() {
    every { NetUtil.fetch(any()) } returns
      "       NETWORK_PASSPHRASE=\"Public Global Stellar Network ; September 2015\"\n"

    assertThrows<SepException> {
      Sep10Helper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN, false)
    }

    every { NetUtil.fetch(any()) } answers { throw IOException("Cannot connect") }
    assertThrows<SepException> {
      Sep10Helper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN, false)
    }

    every { NetUtil.fetch(any()) } returns
      """
      NETWORK_PASSPHRASE="Public Global Stellar Network ; September 2015"
      HORIZON_URL="https://horizon.stellar.org"
      FEDERATION_SERVER="https://preview.lobstr.co/federation/"
      SIGNING_KEY="BADKEY"
      """
    assertThrows<SepException> {
      Sep10Helper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN, false)
    }
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test createChallenge signing error`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every {
      Sep10Challenge.newChallenge(any(), any(), any(), any(), any(), any(), any(), any(), any())
    } answers { throw InvalidSep10ChallengeException("mock exception") }

    assertThrows<SepException> {
      sep10Service.createChallenge(
        ChallengeRequest.builder()
          .account(TEST_ACCOUNT)
          .memo(TEST_MEMO)
          .homeDomain(TEST_HOME_DOMAIN)
          .clientDomain(TEST_CLIENT_DOMAIN)
          .build()
      )
    }
  }

  @Test
  @LockAndMockStatic([Sep10Challenge::class])
  fun `test createChallenge() ok`() {
    every { sep10Config.knownCustodialAccountList } returns listOf(TEST_ACCOUNT)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(null)
        .build()

    assertDoesNotThrow { sep10Service.createChallenge(cr) }
    verify(exactly = 2) { sep10Config.knownCustodialAccountList }
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
    assertEquals(200, response.code)

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

  // ----------------------
  // Signature header tests
  //

  private val clientDomain = "test-wallet.stellar.org"
  private val domainKp =
    SigningKeyPair.fromSecret("SCYVDFYEHNDNTB2UER2FCYSZAYQFAAZ6BDYXL3BWRQWNL327GZUXY7D7")
  // Signing with a domain signer
  private val domainSigner =
    object : DefaultAuthHeaderSigner() {
      override suspend fun createToken(
        claims: Map<String, String>,
        clientDomain: String?,
        issuer: AccountKeyPair?,
      ): String {
        val timeExp = Instant.ofEpochSecond(Clock.System.now().plus(expiration).epochSeconds)
        val builder = createBuilder(timeExp, claims)

        builder.signWith(domainKp.toJava().private, Jwts.SIG.EdDSA)

        return builder.compact()
      }
    }
  private val custodialSigner = DefaultAuthHeaderSigner()
  private val custodialKp =
    SigningKeyPair.fromSecret("SBPPLU2KO3PDBLSDFIWARQSW5SAOIHTJDUQIWN3BQS7KPNMVUDSU37QO")
  private val custodialMemo = "1234567"
  private val authEndpoint = "https://$TEST_WEB_AUTH_DOMAIN/auth"

  @Test
  fun `test valid signature header for custodial`() = runBlocking {
    val params = mapOf("account" to custodialKp.address, "memo" to custodialMemo)
    val token =
      createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = custodialSigner)

    val req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()

    sep10Service.validateAuthorizationToken(req, token, null)
    verify(exactly = 1) { clientFinder.getClientName(null, custodialKp.address) }
  }

  @Test
  fun `test valid signature header for noncustodial`() = runBlocking {
    val account = SigningKeyPair(KeyPair.random())
    val params = mapOf("account" to account.address, "client_domain" to clientDomain)
    val token = createAuthSignToken(account, authEndpoint, params, authHeaderSigner = domainSigner)

    val req = ChallengeRequest.builder().account(account.address).clientDomain(clientDomain).build()

    sep10Service.validateAuthorizationToken(req, token, domainKp.address)
    verify(exactly = 1) { clientFinder.getClientName(clientDomain, any()) }
  }

  @Test
  fun `test http works for testnet`() = runBlocking {
    val params = mapOf("account" to custodialKp.address, "memo" to custodialMemo)
    val token =
      createAuthSignToken(
        custodialKp,
        authEndpoint.replace("https", "http"),
        params,
        authHeaderSigner = custodialSigner,
      )

    val req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()

    sep10Service.validateAuthorizationToken(req, token, null)
    verify(exactly = 1) { clientFinder.getClientName(null, custodialKp.address) }

    // http is not allowed for pubnet
    every { appConfig.stellarNetworkPassphrase } returns PUBLIC.networkPassphrase

    val ex =
      assertThrows<SepValidationException> {
        sep10Service.validateAuthorizationToken(req, token, null)
      }
    assertEquals("Invalid web_auth_endpoint in the signed header", ex.message)
  }

  @Test
  fun `test invalid signature header for custodial`() = runBlocking {
    val params = mapOf("account" to custodialKp.address, "memo" to custodialMemo)
    // Sign with domain singer instead
    val token =
      createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = domainSigner)

    val req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()

    val ex =
      assertThrows<SepValidationException> {
        sep10Service.validateAuthorizationToken(req, token, null)
      }
    assertEquals("Invalid header signature", ex.message)
  }

  @Test
  fun `test invalid signature header for noncustodial`() = runBlocking {
    val params = mapOf("account" to custodialKp.address, "client_domain" to clientDomain)
    val token =
      createAuthSignToken(
        SigningKeyPair(KeyPair.random()),
        authEndpoint,
        params,
        authHeaderSigner = domainSigner,
      )

    val req =
      ChallengeRequest.builder().account(custodialKp.address).clientDomain(clientDomain).build()

    // Use random key as a domain public key
    val ex =
      assertThrows<SepValidationException> {
        sep10Service.validateAuthorizationToken(req, token, KeyPair.random().accountId)
      }
    assertEquals("Invalid header signature", ex.message)
  }

  @Test
  fun `test invalid url`() = runBlocking {
    val params = mapOf("account" to custodialKp.address, "memo" to custodialMemo)
    val token =
      createAuthSignToken(
        custodialKp,
        "https://wrongdomain.com/auth",
        params,
        authHeaderSigner = custodialSigner,
      )

    val req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()

    val ex =
      assertThrows<SepValidationException> {
        sep10Service.validateAuthorizationToken(req, token, null)
      }
    assertEquals("Invalid web_auth_endpoint in the signed header", ex.message)
  }

  @Test
  fun `test params validation`() = runBlocking {
    var params = mutableMapOf<String, String>()
    var token =
      createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = custodialSigner)
    var req = ChallengeRequest.builder().account(custodialKp.address).build()
    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, token, null)
    }

    params = mutableMapOf("account" to custodialKp.address)
    token =
      createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = custodialSigner)
    req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()
    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, token, null)
    }

    params = mutableMapOf("account" to custodialKp.address, "memo" to custodialMemo + "0")
    token =
      createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = custodialSigner)
    req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()
    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, token, null)
    }

    params = mutableMapOf("account" to custodialKp.address, "memo" to custodialMemo)
    token =
      createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = custodialSigner)
    req =
      ChallengeRequest.builder()
        .account(custodialKp.address)
        .memo(custodialMemo)
        .homeDomain("testdomain.com")
        .build()
    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, token, null)
    }

    params =
      mutableMapOf(
        "account" to custodialKp.address,
        "memo" to custodialMemo,
        "home_domain" to "testdomain.com",
      )
    token = createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = domainSigner)
    req =
      ChallengeRequest.builder()
        .account(custodialKp.address)
        .memo(custodialMemo)
        .homeDomain("testdomain.com")
        .clientDomain(clientDomain)
        .build()
    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, token, domainKp.address)
    }

    params =
      mutableMapOf(
        "account" to custodialKp.address,
        "memo" to custodialMemo,
        "home_domain" to "testdomain.com",
        "client_domain" to clientDomain,
      )
    token = createAuthSignToken(custodialKp, authEndpoint, params, authHeaderSigner = domainSigner)
    req =
      ChallengeRequest.builder()
        .account(custodialKp.address)
        .memo(custodialMemo)
        .homeDomain("testdomain.com")
        .clientDomain(clientDomain)
        .build()

    sep10Service.validateAuthorizationToken(req, token, domainKp.address)
    verify(exactly = 1) { clientFinder.getClientName(clientDomain, any()) }
  }

  @Test
  fun `test no authorization header`() {
    val req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()

    every { sep10Config.isRequireAuthHeader }.returns(false)
    sep10Service.validateAuthorizationToken(req, null, null)

    every { sep10Config.isRequireAuthHeader }.returns(true)
    assertThrows<SepMissingAuthHeaderException> {
      sep10Service.validateAuthorizationToken(req, null, null)
    }
  }

  @Test
  fun `test invalid header`() {
    val req = ChallengeRequest.builder().account(custodialKp.address).memo(custodialMemo).build()

    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, "Bearer", null)
    }

    assertThrows<SepValidationException> {
      sep10Service.validateAuthorizationToken(req, "Bearer 1234", null)
    }
  }
}

fun Sep10Service.validateAuthorizationToken(
  request: ChallengeRequest,
  authorization: String?,
  clientSigningKey: String?,
) {
  this.validateAuthorization(
    request,
    authorization?.run { "Bearer $authorization" },
    clientSigningKey,
  )
}

fun Sep10Service.createChallenge(request: ChallengeRequest): ChallengeResponse {
  return this.createChallenge(request, null)
}
