@file:Suppress("unused")

package org.stellar.anchor.sep10

import com.google.gson.annotations.SerializedName
import io.jsonwebtoken.Jwts
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.stream.Stream
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import okhttp3.OkHttpClient
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
import org.stellar.anchor.auth.NonceManager
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.setupMock
import org.stellar.anchor.util.ClientDomainHelper
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.NetUtil
import org.stellar.sdk.*
import org.stellar.sdk.Network.*
import org.stellar.sdk.exception.InvalidSep10ChallengeException
import org.stellar.sdk.operations.ManageDataOperation
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

@ExtendWith(LockAndMockTest::class)
internal class Sep10ServiceTest {
  companion object {
    @JvmStatic
    fun homeDomains(): Stream<String> {
      return Stream.of(null, TEST_HOME_DOMAIN)
    }

    @JvmStatic
    fun stellarNetworks(): Stream<Arguments> {
      return Stream.of(
        Arguments.of("https://horizon-testnet.stellar.org", TESTNET),
        Arguments.of("https://horizon-futurenet.stellar.org", FUTURENET),
      )
    }
  }

  @MockK(relaxed = true) lateinit var stellarNetworkConfig: StellarNetworkConfig
  @MockK(relaxed = true) lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) lateinit var sep10Config: Sep10Config
  @MockK(relaxed = true) lateinit var ledgerClient: LedgerClient
  @MockK(relaxed = true) lateinit var clientFinder: ClientFinder
  @MockK(relaxed = true) lateinit var nonceManager: NonceManager

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

    every { stellarNetworkConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase

    secretConfig.setupMock()

    // Default to "not a replay" so existing tests that don't exercise replay protection keep
    // passing; tests that specifically test replay protection override this.
    every { nonceManager.claim(any(), any()) } returns true

    this.jwtService = spyk(JwtService(secretConfig))
    this.sep10Service =
      Sep10Service(
        stellarNetworkConfig,
        secretConfig,
        sep10Config,
        ledgerClient,
        jwtService,
        clientFinder,
        nonceManager
      )
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

  /**
   * Builds a challenge signed by [clientKeyPair] twice, to test that a duplicate signature from the
   * same signer isn't counted as two independent signers' worth of weight.
   */
  @Synchronized
  fun createTestChallengeSignedTwiceByClient(homeDomain: String): String {
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
          "",
          "",
          memo,
        )
    txn.sign(clientKeyPair)
    txn.sign(clientKeyPair)
    return txn.toEnvelopeXdrBase64()
  }

  /**
   * Builds a challenge shaped correctly (source account = the real SIGNING_KEY's account) but
   * signed by an impostor key instead of the real SIGNING_KEY, to test that a forged/self-signed
   * challenge is rejected.
   */
  @Synchronized
  fun createTestChallengeSignedByWrongServerKey(homeDomain: String): String {
    val now = System.currentTimeMillis() / 1000L
    val realServerKeyPair = KeyPair.fromSecretSeed(TEST_SIGNING_SEED)
    val impostorKeyPair = KeyPair.random()

    val nonce = ByteArray(48)
    SecureRandom().nextBytes(nonce)
    val encodedNonce = Base64.getEncoder().encode(nonce)

    val sourceAccount = Account(realServerKeyPair.accountId, -1L)
    val homeDomainOp =
      ManageDataOperation.builder()
        .name("$homeDomain auth")
        .value(encodedNonce)
        .sourceAccount(clientKeyPair.accountId)
        .build()
    val webAuthDomainOp =
      ManageDataOperation.builder()
        .name("web_auth_domain")
        .value(TEST_WEB_AUTH_DOMAIN.toByteArray())
        .sourceAccount(realServerKeyPair.accountId)
        .build()

    val txn =
      TransactionBuilder(sourceAccount, Network(TESTNET.networkPassphrase))
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds(now, now + 900)).build()
        )
        .setBaseFee(100)
        .addOperation(homeDomainOp)
        .addOperation(webAuthDomainOp)
        .build()

    // Signed by an impostor key instead of the real SIGNING_KEY, even though the transaction's
    // source account is the real server account.
    txn.sign(impostorKeyPair)
    txn.sign(clientKeyPair)
    return txn.toEnvelopeXdrBase64()
  }

  @Test
  fun `test validate challenge rejects a challenge not signed by SIGNING_KEY`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallengeSignedByWrongServerKey(TEST_HOME_DOMAIN)

    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge rejects signature weight below medium threshold`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    val mockSigners =
      listOf(TestSigner(clientKeyPair.accountId, "SIGNER_KEY_TYPE_ED25519", 10, "").toSigner())
    val accountResponse =
      mockk<LedgerClient.Account> {
        every { accountId } returns clientKeyPair.accountId
        every { sequenceNumber } returns 1
        every { signers } returns mockSigners
        every { thresholds.medium } returns 15
      }
    every { ledgerClient.getAccount(any()) } returns accountResponse

    // The client's only signer has weight 10, below the account's medium threshold of 15.
    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge does not double-count a duplicate signature from the same signer`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallengeSignedTwiceByClient(TEST_HOME_DOMAIN)

    val mockSigners =
      listOf(TestSigner(clientKeyPair.accountId, "SIGNER_KEY_TYPE_ED25519", 10, "").toSigner())
    val accountResponse =
      mockk<LedgerClient.Account> {
        every { accountId } returns clientKeyPair.accountId
        every { sequenceNumber } returns 1
        every { signers } returns mockSigners
        every { thresholds.medium } returns 15
      }
    every { ledgerClient.getAccount(any()) } returns accountResponse

    // The single signer's weight (10) is below the threshold (15). If the duplicate signature
    // were double-counted as 20, this would incorrectly succeed -- it must still be rejected.
    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  /** Builds a challenge for [clientKeyPair]'s account, signed by the given [signers] instead. */
  @Synchronized
  fun createTestChallengeSignedBy(homeDomain: String, signers: List<KeyPair>): String {
    val now = System.currentTimeMillis() / 1000L
    val serverSigner = KeyPair.fromSecretSeed(TEST_SIGNING_SEED)
    val memo = MemoId(TEST_MEMO.toLong())
    val txn =
      Sep10ChallengeWrapper.instance()
        .newChallenge(
          serverSigner,
          Network(TESTNET.networkPassphrase),
          clientKeyPair.accountId,
          homeDomain,
          TEST_WEB_AUTH_DOMAIN,
          TimeBounds(now, now + 900),
          "",
          "",
          memo,
        )
    signers.forEach { txn.sign(it) }
    return txn.toEnvelopeXdrBase64()
  }

  @Test
  fun `test validate challenge succeeds with a non-master signer only`() {
    val nonMasterSigner = KeyPair.random()
    val vr = ValidationRequest()
    vr.transaction = createTestChallengeSignedBy(TEST_HOME_DOMAIN, listOf(nonMasterSigner))

    val mockSigners =
      listOf(TestSigner(nonMasterSigner.accountId, "SIGNER_KEY_TYPE_ED25519", 20, "").toSigner())
    val accountResponse =
      mockk<LedgerClient.Account> {
        every { accountId } returns clientKeyPair.accountId
        every { sequenceNumber } returns 1
        every { signers } returns mockSigners
        every { thresholds.medium } returns 20
      }
    every { ledgerClient.getAccount(any()) } returns accountResponse

    // clientKeyPair (the account's own/master key) never signs and isn't even a configured
    // signer; only a non-master signer with sufficient weight does.
    assertDoesNotThrow { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge succeeds with multiple non-master signers meeting threshold together`() {
    val signerA = KeyPair.random()
    val signerB = KeyPair.random()
    val vr = ValidationRequest()
    vr.transaction = createTestChallengeSignedBy(TEST_HOME_DOMAIN, listOf(signerA, signerB))

    val mockSigners =
      listOf(
        TestSigner(signerA.accountId, "SIGNER_KEY_TYPE_ED25519", 10, "").toSigner(),
        TestSigner(signerB.accountId, "SIGNER_KEY_TYPE_ED25519", 10, "").toSigner(),
      )
    val accountResponse =
      mockk<LedgerClient.Account> {
        every { accountId } returns clientKeyPair.accountId
        every { sequenceNumber } returns 1
        every { signers } returns mockSigners
        every { thresholds.medium } returns 15
      }
    every { ledgerClient.getAccount(any()) } returns accountResponse

    // Neither non-master signer alone (weight 10) meets the threshold (15), and the account's
    // own master key never signs -- combined (20), they must still be accepted.
    assertDoesNotThrow { sep10Service.validateChallenge(vr) }
  }

  /** Builds a normal challenge, then adds one extra, illegitimate signature. */
  @Synchronized
  fun createTestChallengeWithExtraSignature(homeDomain: String): String {
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
          "",
          "",
          memo,
        )
    txn.sign(clientKeyPair)
    txn.sign(KeyPair.random())
    return txn.toEnvelopeXdrBase64()
  }

  @Test
  fun `test validate challenge rejects nonexistent account with an extra client signature`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallengeWithExtraSignature(TEST_HOME_DOMAIN)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw AccountNotFoundException(clientKeyPair.accountId)
      }

    // With no client_domain, exactly 2 signatures (server + client) are expected when the
    // account doesn't exist on the ledger; a 3rd signature must be rejected.
    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test createChallenge() returns response with correct network passphrase`() {
    every { sep10Config.knownCustodialAccountList } returns listOf(TEST_ACCOUNT)
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(null)
        .build()

    val response = sep10Service.createChallenge(cr)

    assertEquals(TESTNET.networkPassphrase, response.networkPassphrase)
  }

  @ParameterizedTest
  @CsvSource(value = ["true,test.client.stellar.org", "false,test.client.stellar.org", "false,"])
  @LockAndMockStatic([NetUtil::class, Sep10Challenge::class, ClientDomainHelper::class])
  fun `test create challenge ok`(clientAttributionRequired: Boolean, clientDomain: String?) {
    every { ClientDomainHelper.validateDomainNotPrivateNetwork(any()) } just Runs
    every { ClientDomainHelper.fetchSigningKeyFromClientDomain(any(), any()) } answers
      {
        callOriginal()
      }
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML
    every { NetUtil.fetch(any(), any(), any()) } returns TEST_CLIENT_TOML

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
  fun `test validate challenge claims the nonce with an expiry anchored to the challenge's own max_time`() {
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

    sep10Service.validateChallenge(vr)

    // The claim's expiry must be derived from the challenge transaction's own signed max_time --
    // not from sep10Config.authTimeout read at validation time -- so a config change between
    // issuance and validation can't let cleanup remove the claim early. It must also extend 1
    // second past max_time: the SDK truncates "now" to whole seconds, so it accepts a submission
    // anywhere in [max_time, max_time + 1), not just the exact instant max_time.000 -- anchoring
    // to exactly max_time would let cleanup (sub-second CURRENT_TIMESTAMP) delete the row while
    // the SDK would still accept a replay for up to another second. Read max_time from the same
    // transaction the service parsed, rather than recomputing it, so this doesn't depend on
    // wall-clock timing.
    val txn: Transaction = Transaction.fromEnvelopeXdr(vr.transaction, TESTNET) as Transaction
    val maxTime: Long = txn.timeBounds.maxTime.toLong()
    val expiresAtSlot = slot<Instant>()
    verify(exactly = 1) { nonceManager.claim(any(), capture(expiresAtSlot)) }
    assertEquals(Instant.ofEpochSecond(maxTime).plusSeconds(1), expiresAtSlot.captured)
  }

  @Test
  fun `test validate challenge rejects a replayed challenge`() {
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

    // First validation of this challenge succeeds and claims its nonce.
    every { nonceManager.claim(any(), any()) } returns true
    sep10Service.validateChallenge(vr)

    // Replaying the exact same challenge transaction must be rejected, even though its signature
    // and time bounds are still otherwise valid.
    every { nonceManager.claim(any(), any()) } returns false
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge rejects a replayed challenge when the account does not exist on the ledger`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw AccountNotFoundException(clientKeyPair.accountId)
      }

    // First validation of this challenge succeeds (via the account-not-found branch) and
    // claims its nonce.
    every { nonceManager.claim(any(), any()) } returns true
    sep10Service.validateChallenge(vr)

    // Replaying the exact same challenge transaction must be rejected -- this branch shares
    // generateWebAuthJwt (and therefore the nonce check) with the account-exists branch.
    every { nonceManager.claim(any(), any()) } returns false
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge does not consume the nonce when validation fails for an unrelated reason`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    // First attempt fails for a reason unrelated to replay protection (a transient ledger
    // error). The nonce must not be touched at all for this attempt to fail.
    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw LedgerException("rpc unavailable")
      }
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
    verify(exactly = 0) { nonceManager.claim(any(), any()) }

    // Retrying the exact same challenge once the transient failure clears must succeed --
    // proving the earlier failed attempt didn't burn the nonce.
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

    assertDoesNotThrow { sep10Service.validateChallenge(vr) }
    verify(exactly = 1) { nonceManager.claim(any(), any()) }
  }

  @Test
  fun `test validate challenge stamps client_name resolved by ClientFinder onto the jwt`() {
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
    every { clientFinder.getClientName(null, clientKeyPair.accountId) } returns "vibrant"

    val response = sep10Service.validateChallenge(vr)
    val jwt = jwtService.decode(response.token, Sep10Jwt::class.java)
    assertEquals("vibrant", jwt.clientName)
  }

  @Test
  fun `test validate challenge propagates ClientFinder authorization failure instead of falling back to null`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw AccountNotFoundException(clientKeyPair.accountId)
      }
    every { clientFinder.getClientName(any(), any()) } throws
      SepNotAuthorizedException("Client not found")

    assertThrows<SepNotAuthorizedException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge does not consume the nonce when client name resolution fails`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw AccountNotFoundException(clientKeyPair.accountId)
      }

    // First attempt fails during client name resolution -- part of client authorization, and
    // the last thing that can fail before the nonce is consumed. The nonce must not be touched.
    every { clientFinder.getClientName(any(), any()) } throws
      SepNotAuthorizedException("Client not found")
    assertThrows<SepNotAuthorizedException> { sep10Service.validateChallenge(vr) }
    verify(exactly = 0) { nonceManager.claim(any(), any()) }

    // Retrying the exact same challenge once client name resolution succeeds must succeed --
    // proving the earlier failure didn't burn the nonce.
    every { clientFinder.getClientName(any(), any()) } returns null
    assertDoesNotThrow { sep10Service.validateChallenge(vr) }
    verify(exactly = 1) { nonceManager.claim(any(), any()) }
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
    every { ledgerClient.getAccount(any()) } answers
      {
        throw AccountNotFoundException(clientKeyPair.accountId)
      }
    vr.transaction = createTestChallenge(TEST_CLIENT_DOMAIN, TEST_HOME_DOMAIN, false)

    assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `test validate challenge when client account is not on network`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw AccountNotFoundException(clientKeyPair.accountId)
      }

    sep10Service.validateChallenge(vr)
  }

  @Test
  fun `test validate challenge fails closed on ledger lookup error`() {
    val vr = ValidationRequest()
    vr.transaction = createTestChallenge("", TEST_HOME_DOMAIN, false)

    every { ledgerClient.getAccount(ofType(String::class)) } answers
      {
        throw LedgerException("rpc unavailable")
      }

    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Suppress("CAST_NEVER_SUCCEEDS")
  @Test
  fun `Test validate challenge with bad request`() {
    assertThrows<SepValidationException> { sep10Service.validateChallenge(null) }

    val vr = ValidationRequest()
    vr.transaction = null
    assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
  }

  @Test
  fun `Test validate challenge rejects oversized transaction`() {
    val vr = ValidationRequest()
    vr.transaction = "A".repeat(50_001)
    val ex = assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
    assertEquals("transaction exceeds maximum allowed size", ex.message)
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
  @LockAndMockStatic([NetUtil::class, ClientDomainHelper::class])
  fun `Test create challenge with wildcard matched home domain success`() {
    every { ClientDomainHelper.validateDomainNotPrivateNetwork(any()) } just Runs
    every { ClientDomainHelper.fetchSigningKeyFromClientDomain(any(), any()) } answers
      {
        callOriginal()
      }
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML
    every { NetUtil.fetch(any(), any(), any()) } returns TEST_CLIENT_TOML
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
  @LockAndMockStatic([NetUtil::class, Sep10Challenge::class, ClientDomainHelper::class])
  fun `Test create challenge request with empty memo`() {
    every { ClientDomainHelper.validateDomainNotPrivateNetwork(any()) } just Runs
    every { ClientDomainHelper.fetchSigningKeyFromClientDomain(any(), any()) } answers
      {
        callOriginal()
      }
    every { NetUtil.fetch(any()) } returns TEST_CLIENT_TOML
    every { NetUtil.fetch(any(), any(), any()) } returns TEST_CLIENT_TOML
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
  @LockAndMockStatic([ClientDomainHelper::class])
  fun `test validateChallengeRequestClient rejects client_domain outside an explicit allow list without fetching it`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { sep10Config.clientAllowList } returns listOf("known-wallet")
    every { sep10Config.allowedClientDomains } returns listOf("known-wallet.example.com")

    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain("attacker.example.com")
        .build()

    assertThrows<SepNotAuthorizedException> { sep10Service.validateChallengeRequestClient(cr) }

    verify(exactly = 0) { ClientDomainHelper.fetchSigningKeyFromClientDomain(any(), any()) }
  }

  @Test
  fun `test validateChallengeRequestClient allows any client_domain when no explicit allow list is configured`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { sep10Config.clientAllowList } returns null
    every { sep10Config.allowedClientDomains } returns emptyList()

    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain("anything.example.com")
        .build()

    assertDoesNotThrow { sep10Service.validateChallengeRequestClient(cr) }
  }

  @Test
  fun `test validateChallengeRequestClient allows an unlisted client_domain when clients exist only for unrelated config`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { sep10Config.clientAllowList } returns null
    every { sep10Config.allowedClientDomains } returns listOf("wallet-server:8092")

    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain("localhost:8092")
        .build()

    assertDoesNotThrow { sep10Service.validateChallengeRequestClient(cr) }
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

  @Test
  fun `test createChallenge() with missing account`() {
    every { sep10Config.isClientAttributionRequired } returns false
    val cr =
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(TEST_CLIENT_DOMAIN)
        .build()
    cr.account = null

    assertThrows<SepValidationException> { sep10Service.createChallenge(cr) }
  }

  @Test
  fun `test validate challenge rejects a malformed transaction value`() {
    val vr = ValidationRequest()
    vr.transaction = "this-is-not-a-valid-base64-xdr-transaction"

    val ex = assertThrows<SepValidationException> { sep10Service.validateChallenge(vr) }
    assertEquals("Invalid challenge transaction.", ex.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["ABC", "12AB", "-1", Integer.MIN_VALUE.toString()])
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
    every { sep10Service.fetchSigningKeyFromClientDomainBounded(any()) } returns
      clientKeyPair.accountId
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
    verify(exactly = 1) { sep10Service.fetchSigningKeyFromClientDomainBounded(TEST_CLIENT_DOMAIN) }
    // Given
    every { sep10Service.fetchSigningKeyFromClientDomainBounded(any()) } throws
      IOException("mock error")
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
  fun `test createChallengeResponse() does not touch the NonceManager`() {
    // The challenge's transaction hash is claimed as a nonce only at validation time (see
    // generateWebAuthJwt), not pre-registered at creation -- creating a challenge is an
    // unauthenticated request and must not write to the nonce store.
    sep10Service.createChallengeResponse(
      ChallengeRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .homeDomain(TEST_HOME_DOMAIN)
        .clientDomain(null)
        .build(),
      MemoId(1234567890),
      null,
    )

    verify { nonceManager wasNot Called }
  }

  @Test
  @LockAndMockStatic([NetUtil::class, ClientDomainHelper::class])
  fun `test getClientAccountId failure`() {
    every { ClientDomainHelper.validateDomainNotPrivateNetwork(any()) } just Runs
    every { ClientDomainHelper.fetchSigningKeyFromClientDomain(any(), any()) } answers
      {
        callOriginal()
      }
    every { NetUtil.fetch(any()) } returns
      "       NETWORK_PASSPHRASE=\"Public Global Stellar Network ; September 2015\"\n"

    assertThrows<SepException> {
      ClientDomainHelper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN, false)
    }

    every { NetUtil.fetch(any()) } answers { throw IOException("Cannot connect") }
    assertThrows<SepException> {
      ClientDomainHelper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN, false)
    }

    every { NetUtil.fetch(any()) } returns
      """
      NETWORK_PASSPHRASE="Public Global Stellar Network ; September 2015"
      HORIZON_URL="https://horizon.stellar.org"
      FEDERATION_SERVER="https://preview.lobstr.co/federation/"
      SIGNING_KEY="BADKEY"
      """
    assertThrows<SepException> {
      ClientDomainHelper.fetchSigningKeyFromClientDomain(TEST_CLIENT_DOMAIN, false)
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

  // ----------------------
  // Signature header tests
  //

  private val clientDomain = "test-wallet.stellar.org"
  private val domainKp = SigningKeyPair(KeyPair.random())
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
  private val custodialKp = SigningKeyPair(KeyPair.random())
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
    every { stellarNetworkConfig.stellarNetworkPassphrase } returns PUBLIC.networkPassphrase

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
