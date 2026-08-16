package org.stellar.anchor.ledger

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.TestConstants.Companion.TEST_HOME_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_SIGNING_SEED
import org.stellar.anchor.TestConstants.Companion.TEST_WEB_AUTH_DOMAIN
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.sep10.Sep10Service
import org.stellar.anchor.setupMock
import org.stellar.sdk.*
import org.stellar.sdk.Network.TESTNET
import org.stellar.sdk.exception.InvalidSep10ChallengeException
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse
import org.stellar.sdk.xdr.AccountEntry
import org.stellar.sdk.xdr.Int64
import org.stellar.sdk.xdr.LedgerEntry
import org.stellar.sdk.xdr.LedgerEntryType
import org.stellar.sdk.xdr.SequenceNumber
import org.stellar.sdk.xdr.Signer
import org.stellar.sdk.xdr.SignerKey
import org.stellar.sdk.xdr.SignerKeyType.SIGNER_KEY_TYPE_ED25519
import org.stellar.sdk.xdr.String32
import org.stellar.sdk.xdr.Thresholds
import org.stellar.sdk.xdr.Uint256
import org.stellar.sdk.xdr.Uint32
import org.stellar.sdk.xdr.XdrString
import org.stellar.sdk.xdr.XdrUnsignedInteger

/**
 * Regression tests for HackerOne #3893785. `StellarRpc.getAccount()` used to widen the on-chain
 * `uint8` threshold and master-weight bytes to `int`/`long` via an unmasked cast, so any byte in
 * `[128,255]` sign-extended to a negative number, which the real Stellar SDK then compared with a
 * signed `if_icmpge`. These tests wire the real `StellarRpc` and `Sep10Service` classes together
 * (only the Soroban-RPC network boundary is stubbed, exactly as `StellarRpcTest` already does) and
 * run real, freshly-signed SEP-10 challenges through them, so the SDK's real threshold-comparison
 * bytecode is what decides pass/fail.
 */
class Sep10RpcThresholdSignExtensionTest {
  private val serverKeyPair = KeyPair.fromSecretSeed(TEST_SIGNING_SEED)

  private fun buildService(stellarRpc: StellarRpc): Sep10Service {
    val stellarNetworkConfig = mockk<StellarNetworkConfig>(relaxed = true)
    every { stellarNetworkConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase

    val secretConfig = mockk<SecretConfig>(relaxed = true)
    secretConfig.setupMock()

    val sep10Config = mockk<Sep10Config>(relaxed = true)
    every { sep10Config.webAuthDomain } returns TEST_WEB_AUTH_DOMAIN
    every { sep10Config.homeDomains } returns listOf(TEST_HOME_DOMAIN)

    val clientFinder = mockk<ClientFinder>(relaxed = true)
    val jwtService = JwtService(secretConfig)

    return Sep10Service(
      stellarNetworkConfig,
      secretConfig,
      sep10Config,
      stellarRpc,
      jwtService,
      clientFinder
    )
  }

  private fun stellarRpcStubbedWith(
    accountId: String,
    thresholdBytes: ByteArray,
    coSigners: List<Pair<KeyPair, Long>>,
  ): StellarRpc {
    val xdrSigners =
      coSigners
        .map { (kp, weight) ->
          Signer().apply {
            key =
              SignerKey().apply {
                discriminant = SIGNER_KEY_TYPE_ED25519
                ed25519 = Uint256().apply { uint256 = kp.publicKey }
              }
            this.weight = Uint32(XdrUnsignedInteger(weight))
          }
        }
        .toTypedArray()

    val accountEntry =
      AccountEntry.builder()
        .accountID(KeyPair.fromAccountId(accountId).xdrAccountId)
        .balance(Int64().apply { int64 = 100_000_000L })
        .seqNum(SequenceNumber().apply { sequenceNumber = Int64().apply { int64 = 1L } })
        .numSubEntries(Uint32(XdrUnsignedInteger(0)))
        .inflationDest(null)
        .flags(Uint32(XdrUnsignedInteger(0)))
        .homeDomain(String32().apply { string32 = XdrString("") })
        .thresholds(Thresholds().apply { thresholds = thresholdBytes })
        .signers(xdrSigners)
        .ext(AccountEntry.AccountEntryExt.builder().discriminant(0).build())
        .build()

    val ledgerEntryData =
      LedgerEntry.LedgerEntryData.builder()
        .discriminant(LedgerEntryType.ACCOUNT)
        .account(accountEntry)
        .build()

    val response =
      GetLedgerEntriesResponse(
        listOf(
          GetLedgerEntriesResponse.LedgerEntryResult("", ledgerEntryData.toXdrBase64(), 1L, null)
        ),
        1L,
      )

    val stellarRpc = StellarRpc("https://stub-rpc.invalid")
    stellarRpc.sorobanServer = mockk { every { getLedgerEntries(any()) } returns response }
    return stellarRpc
  }

  private fun signedChallenge(clientAccountId: String, signers: List<KeyPair>): String {
    val now = System.currentTimeMillis() / 1000L
    val txn =
      Sep10Challenge.newChallenge(
        serverKeyPair,
        Network(TESTNET.networkPassphrase),
        clientAccountId,
        TEST_HOME_DOMAIN,
        TEST_WEB_AUTH_DOMAIN,
        TimeBounds(now, now + 900),
        "",
        "",
        MemoId(0L),
      )
    signers.forEach { txn.sign(it) }
    return txn.toEnvelopeXdrBase64()
  }

  @Test
  fun `A0 - StellarRpc getAccount() parses an on-chain med_threshold of 200 as 200, not -56`() {
    val client = KeyPair.random()
    val stellarRpc =
      stellarRpcStubbedWith(
        client.accountId,
        byteArrayOf(0, 100.toByte(), 200.toByte(), 100.toByte()),
        emptyList(),
      )

    val account = stellarRpc.getAccount(client.accountId)

    assertEquals(200, account.thresholds.medium)
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, 127, 128, 200, 254, 255])
  fun `NEGATIVE CONTROL - no on-chain uint8 threshold or master-weight byte ever parses as negative`(
    byteValue: Int
  ) {
    val client = KeyPair.random()
    val b = byteValue.toByte()
    val stellarRpc = stellarRpcStubbedWith(client.accountId, byteArrayOf(b, b, b, b), emptyList())

    val account = stellarRpc.getAccount(client.accountId)

    assertEquals(byteValue, account.thresholds.low)
    assertEquals(byteValue, account.thresholds.medium)
    assertEquals(byteValue, account.thresholds.high)
    val masterSigner = account.signers.single { it.key == client.accountId }
    assertEquals(byteValue.toLong(), masterSigner.weight)
    assertTrue(account.thresholds.low >= 0)
    assertTrue(account.thresholds.medium >= 0)
    assertTrue(account.thresholds.high >= 0)
    assertTrue(masterSigner.weight >= 0)
  }

  @Test
  fun `ATTACK A - a single weight-100 co-signer is rejected when med_threshold is 200`() {
    val client = KeyPair.random()
    val coSigner = KeyPair.random()
    val stellarRpc =
      stellarRpcStubbedWith(
        client.accountId,
        byteArrayOf(0, 100.toByte(), 200.toByte(), 100.toByte()),
        listOf(coSigner to 100L),
      )
    val sep10Service = buildService(stellarRpc)

    val vr = ValidationRequest()
    vr.transaction = signedChallenge(client.accountId, listOf(coSigner))

    val ex = assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }

    assertEquals("Signers with weight 100 do not meet threshold 200.", ex.message)
  }

  @Test
  fun `ATTACK B - a revoked weight-0 master key alone is rejected when med_threshold is 200`() {
    val client = KeyPair.random()
    val stellarRpc =
      stellarRpcStubbedWith(
        client.accountId,
        byteArrayOf(0, 100.toByte(), 200.toByte(), 100.toByte()),
        emptyList(),
      )
    val sep10Service = buildService(stellarRpc)

    val vr = ValidationRequest()
    vr.transaction = signedChallenge(client.accountId, listOf(client))

    val ex = assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }

    assertEquals("Signers with weight 0 do not meet threshold 200.", ex.message)
  }

  @Test
  fun `ATTACK C - the rightful sole owner authenticates when master weight is 200 and med_threshold is 100`() {
    val client = KeyPair.random()
    val stellarRpc =
      stellarRpcStubbedWith(
        client.accountId,
        byteArrayOf(200.toByte(), 100.toByte(), 100.toByte(), 100.toByte()),
        emptyList(),
      )
    val sep10Service = buildService(stellarRpc)

    val vr = ValidationRequest()
    vr.transaction = signedChallenge(client.accountId, listOf(client))

    val response = assertDoesNotThrow { sep10Service.validateChallenge(vr) }

    assertEquals(true, response.token.isNotEmpty())
  }

  @Test
  fun `CONTROL - a single weight-50 co-signer is correctly rejected when med_threshold is 100`() {
    val client = KeyPair.random()
    val coSigner = KeyPair.random()
    val stellarRpc =
      stellarRpcStubbedWith(
        client.accountId,
        byteArrayOf(0, 100.toByte(), 100.toByte(), 100.toByte()),
        listOf(coSigner to 50L),
      )
    val sep10Service = buildService(stellarRpc)

    val vr = ValidationRequest()
    vr.transaction = signedChallenge(client.accountId, listOf(coSigner))

    val ex = assertThrows<InvalidSep10ChallengeException> { sep10Service.validateChallenge(vr) }

    assertEquals("Signers with weight 50 do not meet threshold 100.", ex.message)
  }
}
