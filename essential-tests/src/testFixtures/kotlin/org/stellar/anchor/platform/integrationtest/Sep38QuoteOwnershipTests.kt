package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep38.Sep38Context
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

/**
 * A SEP-38 quote can be bound to a SEP-6, SEP-24 or SEP-31 transaction only by the identity that
 * created it (SEP38OW). A fresh victim creates the quote; a fresh attacker, who never saw the quote
 * but knows its id, tries to use it. Every rejection is paired with the victim then using the same
 * quote successfully, which also proves the rejected attempt left the quote unbound.
 */
@Order(RUN_AFTER_PLATFORM_API_TESTS)
class Sep38QuoteOwnershipTests : IntegrationTestBase(TestConfig()) {
  private val gson = GsonUtils.getInstance()
  private val sep24Url = toml.getString("TRANSFER_SERVER_SEP0024")
  private val sep6Url = toml.getString("TRANSFER_SERVER")
  private val sep31Url = toml.getString("DIRECT_PAYMENT_SERVER")
  private val sep38Url = toml.getString("ANCHOR_QUOTE_SERVER")
  private val sep12Url = toml.getString("KYC_SERVER")

  private class Identity(val jwt: String) {
    fun sep38(url: String) = Sep38Client(url, jwt)
  }

  private fun newIdentity(): Identity {
    val jwt = runBlocking { anchor.auth().authenticate(SigningKeyPair(KeyPair.random())) }.token
    return Identity(jwt)
  }

  private fun identityWithMemo(keyPair: KeyPair, memoId: ULong): Identity {
    val jwt =
      runBlocking { anchor.auth().authenticate(SigningKeyPair(keyPair), memoId = memoId) }.token
    return Identity(jwt)
  }

  /** The wallet's own identity, whose account trusts USDC (the SEP-6 deposit destination). */
  private fun walletIdentity() = Identity(this.token.token)

  private fun errorMessage(ex: SepException): String =
    JsonParser.parseString(ex.message).asJsonObject.get("error").asString

  private fun postQuote(
    owner: Identity,
    sellAsset: String,
    buyAsset: String,
    context: Sep38Context,
    amount: String,
  ): String = owner.sep38(sep38Url).postQuote(sellAsset, amount, buyAsset, context).id

  private fun sep24DepositRequest(quoteId: String) =
    mapOf(
      "asset_code" to "USDC",
      "asset_issuer" to USDC,
      "source_asset" to USD,
      "amount" to SEP24_AMOUNT,
      "quote_id" to quoteId,
    )

  private fun sep24WithdrawalRequest(quoteId: String) =
    mapOf(
      "asset_code" to "USDC",
      "asset_issuer" to USDC,
      "destination_asset" to USD,
      "amount" to SEP24_AMOUNT,
      "quote_id" to quoteId,
    )

  private fun sep6DepositExchangeRequest(quoteId: String, account: String? = null) = buildMap {
    put("destination_asset", "USDC")
    put("source_asset", USD)
    put("amount", SEP6_AMOUNT)
    put("type", "SWIFT")
    put("quote_id", quoteId)
    if (account != null) put("account", account)
  }

  private fun sep6WithdrawExchangeRequest(quoteId: String) =
    mapOf(
      "destination_asset" to USD,
      "source_asset" to "USDC",
      "amount" to SEP6_AMOUNT,
      "type" to "bank_account",
      "quote_id" to quoteId,
    )

  private fun sep31Request(quoteId: String): Sep31PostTransactionRequest {
    val request =
      gson.fromJson(
        """{
          "amount": "$SEP31_AMOUNT",
          "asset_code": "USDC",
          "asset_issuer": "$USDC",
          "funding_method": "SEPA",
          "fields": {
            "transaction": {
              "receiver_routing_number": "r0123",
              "receiver_account_number": "a0456",
              "type": "SWIFT"
            }
          }
        }""",
        Sep31PostTransactionRequest::class.java,
      )
    request.quoteId = quoteId
    return request
  }

  // SEP38OW-01
  @Test
  fun `test sep24 deposit with a quote created by another identity is rejected and the owner can still use it`() {
    val victim = newIdentity()
    val attacker = newIdentity()
    val quoteId = postQuote(victim, USD, "stellar:USDC:$USDC", Sep38Context.SEP24, SEP24_AMOUNT)
    val attackerClient = Sep24Client(sep24Url, attacker.jwt)

    val ex =
      assertThrows<SepValidationException> { attackerClient.deposit(sep24DepositRequest(quoteId)) }

    assertEquals("Quote not found", errorMessage(ex))
    assertEquals(0, attackerClient.getTransactions(mapOf("asset_code" to "USDC")).transactions.size)
    // SEP38OW-06, SEP38OW-08
    assertNotNull(Sep24Client(sep24Url, victim.jwt).deposit(sep24DepositRequest(quoteId)).id)
  }

  // SEP38OW-01
  @Test
  fun `test sep24 withdrawal with a quote created by another identity is rejected and the owner can still use it`() {
    val victim = newIdentity()
    val attacker = newIdentity()
    val quoteId = postQuote(victim, "stellar:USDC:$USDC", USD, Sep38Context.SEP24, SEP24_AMOUNT)
    val attackerClient = Sep24Client(sep24Url, attacker.jwt)

    val ex =
      assertThrows<SepValidationException> {
        attackerClient.withdraw(sep24WithdrawalRequest(quoteId))
      }

    assertEquals("Quote not found", errorMessage(ex))
    assertEquals(0, attackerClient.getTransactions(mapOf("asset_code" to "USDC")).transactions.size)
    assertNotNull(Sep24Client(sep24Url, victim.jwt).withdraw(sep24WithdrawalRequest(quoteId)).id)
  }

  // SEP38OW-02
  @Test
  fun `test sep6 deposit-exchange with a quote created by another identity is rejected and the owner can still use it`() {
    val victim = walletIdentity()
    val attacker = newIdentity()
    val quoteId = postQuote(victim, USD, "stellar:USDC:$USDC", Sep38Context.SEP6, SEP6_AMOUNT)
    val attackerClient = Sep6Client(sep6Url, attacker.jwt)

    val ex =
      assertThrows<SepValidationException> {
        attackerClient.deposit(sep6DepositExchangeRequest(quoteId), exchange = true)
      }

    assertEquals("Quote not found", errorMessage(ex))
    assertEquals(0, attackerClient.getTransactions(mapOf("asset_code" to "USDC")).transactions.size)
    val walletAccount = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId
    val ownerResponse =
      Sep6Client(sep6Url, victim.jwt)
        .deposit(sep6DepositExchangeRequest(quoteId, walletAccount), exchange = true)
    assertNotNull(ownerResponse.id)
  }

  // SEP38OW-03
  @Test
  fun `test sep6 withdraw-exchange with a quote created by another identity is rejected and the owner can still use it`() {
    val victim = walletIdentity()
    val attacker = newIdentity()
    val quoteId = postQuote(victim, "stellar:USDC:$USDC", USD, Sep38Context.SEP6, SEP6_AMOUNT)
    val attackerClient = Sep6Client(sep6Url, attacker.jwt)

    val ex =
      assertThrows<SepValidationException> {
        attackerClient.withdraw(sep6WithdrawExchangeRequest(quoteId), exchange = true)
      }

    assertEquals("Quote not found", errorMessage(ex))
    assertEquals(0, attackerClient.getTransactions(mapOf("asset_code" to "USDC")).transactions.size)
    val ownerResponse =
      Sep6Client(sep6Url, victim.jwt)
        .withdraw(sep6WithdrawExchangeRequest(quoteId), exchange = true)
    assertNotNull(ownerResponse.id)
  }

  // SEP38OW-04
  @Test
  fun `test sep31 transaction with a quote created by another identity is rejected and the owner can still use it`() {
    val victim = walletIdentity()
    val attacker = newIdentity()
    val quoteId = postQuote(victim, "stellar:USDC:$USDC", JPYC, Sep38Context.SEP31, SEP31_AMOUNT)

    val ex =
      assertThrows<SepValidationException> {
        Sep31Client(sep31Url, attacker.jwt).postTransaction(sep31Request(quoteId))
      }

    assertEquals("quote(id=$quoteId) was not found.", errorMessage(ex))
    val sep12 = Sep12Client(sep12Url, victim.jwt)
    val sender =
      sep12.putCustomer(gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java))!!
    val receiver =
      sep12.putCustomer(gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java))!!
    val ownerRequest =
      sep31Request(quoteId).also {
        it.senderId = sender.id
        it.receiverId = receiver.id
      }
    assertNotNull(Sep31Client(sep31Url, victim.jwt).postTransaction(ownerRequest).id)
  }

  // SEP38OW-05
  @Test
  fun `test a non-owner gets not found, not already used, for a quote the owner already bound`() {
    val victim = newIdentity()
    val attacker = newIdentity()
    val quoteId = postQuote(victim, USD, "stellar:USDC:$USDC", Sep38Context.SEP24, SEP24_AMOUNT)
    assertNotNull(Sep24Client(sep24Url, victim.jwt).deposit(sep24DepositRequest(quoteId)).id)

    val ex =
      assertThrows<SepValidationException> {
        Sep24Client(sep24Url, attacker.jwt).deposit(sep24DepositRequest(quoteId))
      }

    assertEquals("Quote not found", errorMessage(ex))
    // The owner, by contrast, is told the quote is used.
    val ownerAgain =
      assertThrows<SepValidationException> {
        Sep24Client(sep24Url, victim.jwt).deposit(sep24DepositRequest(quoteId))
      }
    assertEquals("quote(id=$quoteId) has already been used", errorMessage(ownerAgain))
  }

  // SEP38OW-07
  @Test
  fun `test the same account under another memo cannot use the quote`() {
    val keyPair = KeyPair.random()
    val creator = identityWithMemo(keyPair, 1UL)
    val otherMemo = identityWithMemo(keyPair, 2UL)
    val quoteId = postQuote(creator, USD, "stellar:USDC:$USDC", Sep38Context.SEP24, SEP24_AMOUNT)

    val ex =
      assertThrows<SepValidationException> {
        Sep24Client(sep24Url, otherMemo.jwt).deposit(sep24DepositRequest(quoteId))
      }

    assertEquals("Quote not found", errorMessage(ex))
    assertNotNull(Sep24Client(sep24Url, creator.jwt).deposit(sep24DepositRequest(quoteId)).id)
  }

  private companion object {
    const val USDC = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    const val USD = "iso4217:USD"
    const val JPYC = "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    const val SEP24_AMOUNT = "5"
    const val SEP6_AMOUNT = "10"
    const val SEP31_AMOUNT = "10"
  }
}
