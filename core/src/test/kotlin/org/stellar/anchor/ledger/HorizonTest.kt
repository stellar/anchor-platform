package org.stellar.anchor.ledger

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.network.Horizon
import org.stellar.sdk.Asset
import org.stellar.sdk.AssetTypeCreditAlphaNum
import org.stellar.sdk.Server
import org.stellar.sdk.TrustLineAsset
import org.stellar.sdk.requests.AccountsRequestBuilder
import org.stellar.sdk.responses.AccountResponse
import org.stellar.sdk.responses.AccountResponse.Balance
import org.stellar.sdk.responses.SubmitTransactionAsyncResponse.TransactionStatus
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus.*
import org.stellar.sdk.xdr.SignerKeyType.*

internal class HorizonTest {
  companion object {
    private const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
    private const val TEST_HORIZON_PASSPHRASE = "Test SDF Network ; September 2015"
    val appConfig = mockk<AppConfig>()

    @JvmStatic
    @BeforeAll
    fun setup() {
      every { appConfig.horizonUrl } returns TEST_HORIZON_URI
      every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE
    }
  }

  @Test
  fun `test the correctness of Horizon creation`() {
    val horizon = Horizon(appConfig)

    assertNotNull(horizon.server)
  }

  @Test
  fun test_hasTrustline_native() {
    val horizon = Horizon(appConfig)

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
    val server = mockk<Server>()
    val account = "testAccount"
    val asset = "stellar:USDC:issuerAccount1"
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val accountResponse: AccountResponse = mockk()
    val balance1: Balance = mockk()
    val balance2: Balance = mockk()
    val asset1: AssetTypeCreditAlphaNum = mockk()
    val asset2: AssetTypeCreditAlphaNum = mockk()

    every { server.accounts() } returns accountsRequestBuilder
    every { accountsRequestBuilder.account(account) } returns accountResponse

    every { asset1.code } returns "USDC"
    every { asset1.issuer } returns "issuerAccount1"
    every { asset2.code } returns "USDC"
    every { asset2.issuer } returns "issuerAccount2"

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
    val server = mockk<Server>()
    val account = "testAccount"
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
    every { asset2.issuer } returns "issuerAccount1"
    every { asset3.code } returns "USDC"
    every { asset3.issuer } returns "issuerAccount2"

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
}
