package org.stellar.anchor.platform.integrationtest

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.horizon.SigningKeyPair

class Sep24AccountIsolationTests : IntegrationTestBase(TestConfig()) {
  private fun authenticateWithMemo(keyPair: SigningKeyPair, memoId: ULong) = runBlocking {
    anchor.auth().authenticate(keyPair, memoId = memoId)
  }

  private fun authenticateWithoutMemo(keyPair: SigningKeyPair) = runBlocking {
    anchor.auth().authenticate(keyPair)
  }

  @Test
  fun `test sep24 GET transactions does not leak another memo's transactions on a shared account`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val noMemoToken = authenticateWithoutMemo(sharedKeyPair)
    val memoAToken = authenticateWithMemo(sharedKeyPair, 111UL)
    val memoBToken = authenticateWithMemo(sharedKeyPair, 222UL)

    val noMemoTxnId =
      runBlocking { anchor.sep24().deposit(USDC, noMemoToken, mapOf("amount" to "1")) }.id
    val memoATxnId =
      runBlocking { anchor.sep24().deposit(USDC, memoAToken, mapOf("amount" to "1")) }.id
    val memoBTxnId =
      runBlocking { anchor.sep24().deposit(USDC, memoBToken, mapOf("amount" to "1")) }.id

    val listedIds =
      runBlocking { anchor.sep24().getTransactionsForAsset(USDC, noMemoToken) }.map { it.id }

    assertThat(listedIds)
      .`as`("caller's own no-memo transaction should be visible in its own list")
      .contains(noMemoTxnId)
    assertThat(listedIds)
      .`as`(
        "GET /transactions leaked another memo's transaction ($memoATxnId) to a bare-account caller sharing the same Stellar account"
      )
      .doesNotContain(memoATxnId)
    assertThat(listedIds)
      .`as`(
        "GET /transactions leaked another memo's transaction ($memoBTxnId) to a bare-account caller sharing the same Stellar account"
      )
      .doesNotContain(memoBTxnId)
  }

  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
  }
}
