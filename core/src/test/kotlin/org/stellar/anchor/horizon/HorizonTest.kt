package org.stellar.anchor.horizon

import io.mockk.every
import io.mockk.mockk
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.config.AppConfig
import org.stellar.sdk.AssetTypeCreditAlphaNum
import org.stellar.sdk.Server
import org.stellar.sdk.requests.AccountsRequestBuilder
import org.stellar.sdk.responses.AccountResponse
import org.stellar.sdk.responses.AccountResponse.Balance

internal class HorizonTest {
  companion object {
    const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
    const val TEST_HORIZON_PASSPHRASE = "Test SDF Network ; September 2015"
  }

  @Test
  fun `test the correctness of Horizon creation`() {
    val appConfig = mockk<AppConfig>()
    every { appConfig.horizonUrl } returns TEST_HORIZON_URI
    every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE

    val horizon = Horizon(appConfig)

    assertNotNull(horizon.server)
    assertEquals(TEST_HORIZON_URI, horizon.horizonUrl)
    assertEquals(TEST_HORIZON_PASSPHRASE, horizon.stellarNetworkPassphrase)
  }

  @Test
  fun test_isTrustlineConfigured_native() {
    val appConfig = mockk<AppConfig>()
    every { appConfig.horizonUrl } returns TEST_HORIZON_URI
    every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE

    val horizon = Horizon(appConfig)

    val account = "testAccount"
    val asset = "stellar:native"

    assertTrue(horizon.isTrustlineConfigured(account, asset))
  }

  @Test
  fun test_isTrustlineConfigured_horizonError() {
    val appConfig = mockk<AppConfig>()
    val server = mockk<Server>()
    val account = "testAccount"
    val asset = "stellar:USDC:issuerAccount"

    every { appConfig.horizonUrl } returns TEST_HORIZON_URI
    every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE
    every { server.accounts() } throws RuntimeException("Horizon error")

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.isTrustlineConfigured(account, asset) } answers { callOriginal() }

    assertThrows<RuntimeException> { horizon.isTrustlineConfigured(account, asset) }
  }

  @Test
  fun test_isTrustlineConfigured_present() {
    val appConfig = mockk<AppConfig>()
    val server = mockk<Server>()
    val account = "testAccount"
    val asset = "stellar:USDC:issuerAccount1"
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val accountResponse: AccountResponse = mockk()
    val balance1: Balance = mockk()
    val balance2: Balance = mockk()
    val asset1: AssetTypeCreditAlphaNum = mockk()
    val asset2: AssetTypeCreditAlphaNum = mockk()

    every { appConfig.horizonUrl } returns TEST_HORIZON_URI
    every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE
    every { server.accounts() } returns accountsRequestBuilder
    every { accountsRequestBuilder.account(account) } returns accountResponse
    every { balance1.getAssetType() } returns "credit_alphanum4"
    every { balance1.getAsset() } returns Optional.of(asset1)
    every { balance2.getAssetType() } returns "credit_alphanum12"
    every { balance2.getAsset() } returns Optional.of(asset2)
    every { asset1.getCode() } returns "USDC"
    every { asset1.getIssuer() } returns "issuerAccount1"
    every { asset2.getCode() } returns "USDC"
    every { asset2.getIssuer() } returns "issuerAccount2"
    every { accountResponse.getBalances() } returns arrayOf(balance1, balance2)

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.isTrustlineConfigured(account, asset) } answers { callOriginal() }

    assertTrue(horizon.isTrustlineConfigured(account, asset))
  }

  @Test
  fun test_isTrustlineConfigured_absent() {
    val appConfig = mockk<AppConfig>()
    val server = mockk<Server>()
    val account = "testAccount"
    val asset = "stellar:USDC:issuerAccount1"
    val accountsRequestBuilder: AccountsRequestBuilder = mockk()
    val accountResponse: AccountResponse = mockk()
    val balance1: Balance = mockk()
    val balance2: Balance = mockk()
    val balance3: Balance = mockk()
    val asset1: AssetTypeCreditAlphaNum = mockk()
    val asset2: AssetTypeCreditAlphaNum = mockk()
    val asset3: AssetTypeCreditAlphaNum = mockk()
    every { server.accounts() } returns accountsRequestBuilder
    every { accountsRequestBuilder.account(account) } returns accountResponse
    every { balance1.getAssetType() } returns "credit_alphanum8"
    every { balance1.getAsset() } returns Optional.of(asset1)
    every { balance2.getAssetType() } returns "credit_alphanum4"
    every { balance2.getAsset() } returns Optional.of(asset2)
    every { balance3.getAssetType() } returns "credit_alphanum4"
    every { balance3.getAsset() } returns Optional.of(asset3)
    every { asset1.getCode() } returns "USDC"
    every { asset1.getIssuer() } returns "issuerAccount1"
    every { asset2.getCode() } returns "SRT"
    every { asset2.getIssuer() } returns "issuerAccount1"
    every { asset3.getCode() } returns "USDC"
    every { asset3.getIssuer() } returns "issuerAccount2"
    every { accountResponse.getBalances() } returns arrayOf(balance1, balance2, balance3)

    every { appConfig.horizonUrl } returns TEST_HORIZON_URI
    every { appConfig.stellarNetworkPassphrase } returns TEST_HORIZON_PASSPHRASE

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.isTrustlineConfigured(account, asset) } answers { callOriginal() }

    assertFalse(horizon.isTrustlineConfigured(account, asset))
  }
}
