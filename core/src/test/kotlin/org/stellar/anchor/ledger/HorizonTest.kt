package org.stellar.anchor.ledger

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.AccountNotFoundException
import org.stellar.anchor.api.exception.LedgerException
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.util.Log
import org.stellar.sdk.Asset
import org.stellar.sdk.AssetTypeCreditAlphaNum
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Server
import org.stellar.sdk.TrustLineAsset
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.requests.AccountsRequestBuilder
import org.stellar.sdk.requests.TransactionsRequestBuilder
import org.stellar.sdk.responses.AccountResponse
import org.stellar.sdk.responses.AccountResponse.Balance
import org.stellar.sdk.responses.SubmitTransactionAsyncResponse.TransactionStatus
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus.*
import org.stellar.sdk.xdr.SignerKeyType.*

internal class HorizonTest {
  companion object {
    private const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
    private const val TEST_HORIZON_PASSPHRASE = "Test SDF Network ; September 2015"
    val stellarNetworkConfig = mockk<StellarNetworkConfig>()

    @JvmStatic
    @BeforeAll
    fun setup() {
      every { stellarNetworkConfig.horizonUrl } returns TEST_HORIZON_URI
      every { stellarNetworkConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE
    }
  }

  @Test
  fun `test the correctness of Horizon creation`() {
    val horizon = Horizon(stellarNetworkConfig)

    assertNotNull(horizon.server)
  }

  @Test
  fun test_hasTrustline_native() {
    val horizon = Horizon(stellarNetworkConfig)

    val account = "testAccount"
    val asset = "native"

    assertTrue(horizon.hasTrustline(account, asset))
  }

  @Test
  fun test_hasTrustline_horizonError() {
    val server = mockk<Server>()
    val account = "testAccount"
    val asset = "stellar:USDC:issuerAccount"

    every { server.accounts() } throws RuntimeException("Horizon error")

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.hasTrustline(account, asset) } answers { callOriginal() }

    assertThrows<RuntimeException> { horizon.hasTrustline(account, asset) }
  }

  @Test
  fun test_hasTrustline_present() {
    val kp = KeyPair.random()
    val server = mockk<Server>()
    val account = kp.accountId
    val asset = "stellar:USDC:${kp.accountId}"
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val accountResponse: AccountResponse = mockk()
    val balance1: Balance = mockk()
    val balance2: Balance = mockk()
    val asset1: AssetTypeCreditAlphaNum = mockk()
    val asset2: AssetTypeCreditAlphaNum = mockk()

    every { server.accounts() } returns accountsRequestBuilder
    every { accountsRequestBuilder.account(account) } returns accountResponse

    every { asset1.code } returns "USDC"
    every { asset1.issuer } returns kp.accountId
    every { asset2.code } returns "USDC"
    every { asset2.issuer } returns kp.accountId

    every { balance1.trustLineAsset } returns
      TrustLineAsset(Asset.createNonNativeAsset(asset1.code, asset1.issuer))
    every { balance2.trustLineAsset } returns
      TrustLineAsset(Asset.createNonNativeAsset(asset2.code, asset2.issuer))
    every { accountResponse.balances } returns listOf(balance1, balance2)

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.hasTrustline(account, asset) } answers { callOriginal() }
    assertTrue(horizon.hasTrustline(account, asset))
  }

  @Test
  fun test_hasTrustline_absent() {
    val kp = KeyPair.random()
    val server = mockk<Server>()
    val account = kp.accountId
    val asset = "stellar:USDC:issuerAccount1"
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val accountResponse: AccountResponse = mockk()
    val balance1: Balance = mockk()
    val balance2: Balance = mockk()
    val balance3: Balance = mockk()
    // val asset1: AssetTypeNative = mockk()
    val asset2: AssetTypeCreditAlphaNum = mockk()
    val asset3: AssetTypeCreditAlphaNum = mockk()
    every { server.accounts() } returns accountsRequestBuilder
    every { accountsRequestBuilder.account(account) } returns accountResponse

    // asset 1 is native asset
    every { asset2.code } returns "SRT"
    every { asset2.issuer } returns kp.accountId
    every { asset3.code } returns "USDC"
    every { asset3.issuer } returns kp.accountId

    every { balance1.trustLineAsset } returns TrustLineAsset(Asset.createNativeAsset())
    every { balance2.trustLineAsset } returns
      TrustLineAsset(Asset.createNonNativeAsset(asset2.code, asset2.issuer))
    every { balance3.trustLineAsset } returns
      TrustLineAsset(Asset.createNonNativeAsset(asset3.code, asset3.issuer))

    every { accountResponse.balances } returns listOf(balance1, balance2, balance3)

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.hasTrustline(account, asset) } answers { callOriginal() }
    assertFalse(horizon.hasTrustline(account, asset))
  }

  @Test
  fun `test getAccount with a genuine 404 falls back to account-not-found`() {
    val server = mockk<Server>()
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val account = KeyPair.random().accountId

    every { server.accounts() } returns accountsRequestBuilder
    every { accountsRequestBuilder.account(account) } throws
      BadRequestException(404, "not found", null, null)

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.getAccount(account) } answers { callOriginal() }

    assertThrows<AccountNotFoundException> { horizon.getAccount(account) }
  }

  @Test
  fun `test getAccount with a non-404 4xx fails closed instead of account-not-found`() {
    val server = mockk<Server>()
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val account = KeyPair.random().accountId

    for (code in listOf(400, 401, 403, 406)) {
      every { server.accounts() } returns accountsRequestBuilder
      every { accountsRequestBuilder.account(account) } throws
        BadRequestException(code, "blocked", null, null)

      val horizon = mockk<Horizon>()
      every { horizon.server } returns server
      every { horizon.getAccount(account) } answers { callOriginal() }

      // AccountNotFoundException extends LedgerException, so asserting the exception is a
      // LedgerException alone would also pass on the vulnerable fallback. The point of this
      // test is that a non-404 4xx must NOT take the account-not-found fallback.
      val exception = assertThrows<LedgerException> { horizon.getAccount(account) }
      assertFalse(
        exception is AccountNotFoundException,
        "HTTP $code must fail closed as a plain LedgerException, not be treated as account-not-found",
      )
    }
  }

  @Test
  fun `test convert() with transaction status`() {
    assertEquals(PENDING, Horizon.toSendTranscationStatus(TransactionStatus.PENDING))
    assertEquals(ERROR, Horizon.toSendTranscationStatus(TransactionStatus.ERROR))
    assertEquals(DUPLICATE, Horizon.toSendTranscationStatus(TransactionStatus.DUPLICATE))
    assertEquals(
      TRY_AGAIN_LATER,
      Horizon.toSendTranscationStatus(TransactionStatus.TRY_AGAIN_LATER),
    )
  }

  @Test
  fun `test getKeyTypeDiscriminant with valid types`() {
    assertEquals(SIGNER_KEY_TYPE_ED25519, Horizon.getKeyTypeDiscriminant("ed25519_public_key"))
    assertEquals(SIGNER_KEY_TYPE_PRE_AUTH_TX, Horizon.getKeyTypeDiscriminant("preauth_tx"))
    assertEquals(SIGNER_KEY_TYPE_HASH_X, Horizon.getKeyTypeDiscriminant("sha256_hash"))
    assertEquals(
      SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD,
      Horizon.getKeyTypeDiscriminant("ed25519_signed_payload"),
    )
  }

  @Test
  fun `test getKeyTypeDiscriminant with invalid type`() {
    val exception =
      assertThrows<IllegalArgumentException> { Horizon.getKeyTypeDiscriminant("invalid_type") }
    kotlin.test.assertEquals("Invalid signer key type: invalid_type", exception.message)
  }

  private fun horizonWhoseTransactionLookupThrows(hash: String, failure: Exception): Horizon {
    val server = mockk<Server>()
    val transactionsRequestBuilder: TransactionsRequestBuilder = mockk()
    every { server.transactions() } returns transactionsRequestBuilder
    every { transactionsRequestBuilder.transaction(hash) } throws failure

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.getTransaction(hash) } answers { callOriginal() }
    return horizon
  }

  @Test
  fun `test getTransaction returns null for a genuine 404`() {
    val hash = "a".repeat(64)
    val horizon =
      horizonWhoseTransactionLookupThrows(hash, BadRequestException(404, "not found", null, null))

    assertEquals(null, horizon.getTransaction(hash))
  }

  @Test
  fun `test getTransaction fails closed on a non-404 4xx instead of reporting not found`() {
    val hash = "b".repeat(64)
    for (code in listOf(400, 401, 403)) {
      val cause = BadRequestException(code, "blocked", null, null)
      val horizon = horizonWhoseTransactionLookupThrows(hash, cause)

      val exception = assertThrows<LedgerException> { horizon.getTransaction(hash) }
      assertEquals(cause, exception.cause, "HTTP $code must keep the SDK exception as the cause")
    }
  }

  @Test
  fun `test getTransaction fails closed when the 4xx status code is unknown`() {
    val hash = "c".repeat(64)
    // The SDK constructor takes a primitive int, so a null code can only come from a subclass or
    // a future SDK; getCode() is an Integer and the fail-closed rule must hold for it.
    val cause = mockk<BadRequestException>()
    every { cause.code } returns null
    every { cause.body } returns "blocked"
    val horizon = horizonWhoseTransactionLookupThrows(hash, cause)

    val exception = assertThrows<LedgerException> { horizon.getTransaction(hash) }
    assertEquals(cause, exception.cause)
  }

  @Test
  fun `test getTransaction logs the hash, status code and body of a rejected non-404 4xx`() {
    val hash = "d".repeat(64)
    val horizon =
      horizonWhoseTransactionLookupThrows(hash, BadRequestException(403, "waf-block", null, null))
    mockkStatic(Log::class)
    try {
      every { Log.errorF(any(), *anyVararg()) } just Runs

      assertThrows<LedgerException> { horizon.getTransaction(hash) }

      verify(exactly = 1) { Log.errorF(any(), hash, 403, "waf-block") }
    } finally {
      unmockkStatic(Log::class)
    }
  }

  @Test
  fun `test getTransaction keeps reporting other network errors with code and body in the message`() {
    val hash = "e".repeat(64)
    val horizon = horizonWhoseTransactionLookupThrows(hash, NetworkException(500, "boom"))

    val exception = assertThrows<LedgerException> { horizon.getTransaction(hash) }
    assertEquals("Error getting transaction: $hash. Code: 500, body: boom", exception.message)
  }
}
