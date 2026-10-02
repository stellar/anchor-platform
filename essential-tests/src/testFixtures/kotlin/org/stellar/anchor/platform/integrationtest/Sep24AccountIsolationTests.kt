package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.client.Sep24Client
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

  private fun sep24Client(jwt: String) = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), jwt)

  @Test
  fun `test sep24 GET transaction hides a transaction belonging to a different account`() {
    val ownerToken = authenticateWithoutMemo(SigningKeyPair(KeyPair.random()))
    val txnId = runBlocking { anchor.sep24().deposit(USDC, ownerToken, mapOf("amount" to "1")) }.id

    assertThat(sep24Client(ownerToken.token).getTransaction(mapOf("id" to txnId)).transaction.id)
      .`as`("the owner must be able to read its own transaction, or the 404 below proves nothing")
      .isEqualTo(txnId)

    val strangerToken = authenticateWithoutMemo(SigningKeyPair(KeyPair.random()))
    val ex =
      assertThrows<SepNotFoundException> {
        sep24Client(strangerToken.token).getTransaction(mapOf("id" to txnId))
      }
    assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep24 GET transaction hides a transaction belonging to a different memo on the same account`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val memoAToken = authenticateWithMemo(sharedKeyPair, 111UL)
    val memoBToken = authenticateWithMemo(sharedKeyPair, 222UL)
    val txnId = runBlocking { anchor.sep24().deposit(USDC, memoAToken, mapOf("amount" to "1")) }.id

    assertThat(sep24Client(memoAToken.token).getTransaction(mapOf("id" to txnId)).transaction.id)
      .`as`("memo A must be able to read its own transaction, or the 404 below proves nothing")
      .isEqualTo(txnId)

    val ex =
      assertThrows<SepNotFoundException> {
        sep24Client(memoBToken.token).getTransaction(mapOf("id" to txnId))
      }
    assertEquals("transaction not found", ex.message)
  }

  /**
   * A 400 body is `{"error": "..."}`, and the client keeps it raw, so the text is extracted here.
   */
  private fun errorMessage(ex: SepException): String =
    JsonParser.parseString(ex.message).asJsonObject.get("error").asString

  /**
   * The owner pages with its own id: proves the id exists and is usable, so a 400 means "hidden".
   */
  private fun assertOwnerCanPage(ownerJwt: String, pagingId: String) {
    sep24Client(ownerJwt).getTransactions(mapOf("asset_code" to "USDC", "paging_id" to pagingId))
  }

  private fun assertPagingRejected(jwt: String, pagingId: String) {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client(jwt).getTransactions(mapOf("asset_code" to "USDC", "paging_id" to pagingId))
      }
    assertEquals("invalid paging_id field: $pagingId", errorMessage(ex))
  }

  @Test
  fun `test sep24 GET transactions rejects a paging_id belonging to a different account`() {
    val ownerToken = authenticateWithoutMemo(SigningKeyPair(KeyPair.random()))
    val txnId = runBlocking { anchor.sep24().deposit(USDC, ownerToken, mapOf("amount" to "1")) }.id
    assertOwnerCanPage(ownerToken.token, txnId)

    val strangerToken = authenticateWithoutMemo(SigningKeyPair(KeyPair.random()))

    assertPagingRejected(strangerToken.token, txnId)
  }

  @Test
  fun `test sep24 GET transactions rejects a paging_id belonging to a different memo on the same account`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val memoAToken = authenticateWithMemo(sharedKeyPair, 111UL)
    val memoBToken = authenticateWithMemo(sharedKeyPair, 222UL)
    val txnId = runBlocking { anchor.sep24().deposit(USDC, memoAToken, mapOf("amount" to "1")) }.id
    assertOwnerCanPage(memoAToken.token, txnId)

    assertPagingRejected(memoBToken.token, txnId)
  }

  @Test
  fun `test sep24 GET transactions rejects a paging_id that does not exist`() {
    val token = authenticateWithoutMemo(SigningKeyPair(KeyPair.random()))

    assertPagingRejected(token.token, UUID.randomUUID().toString())
  }

  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
  }
}
