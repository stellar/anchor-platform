package org.stellar.anchor.platform.integrationtest

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.auth.AuthToken
import org.stellar.walletsdk.auth.WalletSigner
import org.stellar.walletsdk.horizon.SigningKeyPair

class Sep31CustomerOwnershipTests : IntegrationTestBase(TestConfig()) {
  private val sep12Client: Sep12Client = Sep12Client(toml.getString("KYC_SERVER"), this.token.token)
  private val sep31Client: Sep31Client =
    Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), this.token.token)
  private val sep38Client: Sep38Client =
    Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), this.token.token)

  private val walletUrl = config.env["wallet.server.url"]!!
  private val walletDomain = walletUrl.replace("http://", "")
  private val domainSigner = WalletSigner.DomainSigner("$walletUrl/signChallenge")

  private fun authenticateNewIdentity(): String {
    val keyPair = SigningKeyPair(KeyPair.random())
    return runBlocking { anchor.auth().authenticate(keyPair) }.token
  }

  private fun authenticateNewWalletIdentity(): AuthToken {
    val keyPair = SigningKeyPair(KeyPair.random())
    return runBlocking { anchor.auth().authenticate(keyPair) }
  }

  private fun authenticateWithMemo(keyPair: SigningKeyPair, memoId: ULong): String {
    return runBlocking { anchor.auth().authenticate(keyPair, memoId = memoId) }.token
  }

  private fun authenticateNonCustodialIdentity(): String {
    val keyPair = SigningKeyPair(KeyPair.random())
    return runBlocking {
        anchor.auth().authenticate(keyPair, domainSigner, clientDomain = walletDomain)
      }
      .token
  }

  private fun mkTxnRequest(receiverId: String): Sep31PostTransactionRequest {
    val quote =
      sep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val txnRequest = gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
    txnRequest.receiverId = receiverId
    txnRequest.quoteId = quote.id
    return txnRequest
  }

  @Test
  fun `test caller cannot claim a receiver_id already owned by another caller`() {
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val victimReceiver = sep12Client.putCustomer(receiverCustomerRequest)!!
    sep31Client.postTransaction(mkTxnRequest(victimReceiver.id))

    val attackerJwt = authenticateNewIdentity()
    val attackerSep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), attackerJwt)
    val attackerSep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), attackerJwt)
    val quote =
      attackerSep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val attackerTxnRequest =
      gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
    attackerTxnRequest.receiverId = victimReceiver.id
    attackerTxnRequest.quoteId = quote.id

    assertThrows<SepNotAuthorizedException> {
      attackerSep31Client.postTransaction(attackerTxnRequest)
    }
  }

  @Test
  fun `test caller cannot claim a receiver_id that was never used in a transaction`() {
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val victimReceiver = sep12Client.putCustomer(receiverCustomerRequest)!!

    val attackerJwt = authenticateNewIdentity()
    val attackerSep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), attackerJwt)
    val attackerSep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), attackerJwt)
    val quote =
      attackerSep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val attackerTxnRequest =
      gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
    attackerTxnRequest.receiverId = victimReceiver.id
    attackerTxnRequest.quoteId = quote.id

    assertThrows<SepNotAuthorizedException> {
      attackerSep31Client.postTransaction(attackerTxnRequest)
    }
  }

  @Test
  fun `test same caller can reuse a receiver_id it already owns`() {
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val receiver = sep12Client.putCustomer(receiverCustomerRequest)!!

    sep31Client.postTransaction(mkTxnRequest(receiver.id))
    val secondTxn = sep31Client.postTransaction(mkTxnRequest(receiver.id))

    assertNotNull(secondTxn.id)
  }

  @Test
  fun `test a customer id minted via SEP-24 KYC forwarding cannot be claimed by another caller`() =
    runBlocking {
      val victimToken = authenticateNewWalletIdentity()
      val victimSep12Client = Sep12Client(toml.getString("KYC_SERVER"), victimToken.token)

      val depositRequest =
        GsonUtils.getInstance()
          .fromJson(
            sep24DepositWithKycFieldsJson,
            object : TypeToken<HashMap<String, String>>() {}.type
          ) as HashMap<String, String>
      anchor
        .sep24()
        .deposit(
          IssuedAssetId(depositRequest["asset_code"]!!, depositRequest["asset_issuer"]!!),
          victimToken,
          depositRequest,
        )

      val victimCustomer = victimSep12Client.getCustomer()!!

      val attackerJwt = authenticateNewIdentity()
      val attackerSep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), attackerJwt)
      val attackerSep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), attackerJwt)
      val quote =
        attackerSep38Client.postQuote(
          "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
          "10",
          "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        )
      val attackerTxnRequest =
        gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
      attackerTxnRequest.receiverId = victimCustomer.id
      attackerTxnRequest.quoteId = quote.id

      assertThrows<SepNotAuthorizedException> {
        attackerSep31Client.postTransaction(attackerTxnRequest)
      }
    }

  @Test
  fun `test a different account under the same client name cannot claim another user's receiver_id`() {
    val victimJwt = authenticateNonCustodialIdentity()
    val victimSep12Client = Sep12Client(toml.getString("KYC_SERVER"), victimJwt)
    val victimSep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), victimJwt)
    val victimSep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), victimJwt)

    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val victimReceiver = victimSep12Client.putCustomer(receiverCustomerRequest)!!
    val victimQuote =
      victimSep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val victimTxnRequest =
      gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
    victimTxnRequest.receiverId = victimReceiver.id
    victimTxnRequest.quoteId = victimQuote.id
    victimSep31Client.postTransaction(victimTxnRequest)

    val attackerJwt = authenticateNonCustodialIdentity()
    val attackerSep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), attackerJwt)
    val attackerSep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), attackerJwt)
    val attackerQuote =
      attackerSep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val attackerTxnRequest =
      gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
    attackerTxnRequest.receiverId = victimReceiver.id
    attackerTxnRequest.quoteId = attackerQuote.id

    assertThrows<SepNotAuthorizedException> {
      attackerSep31Client.postTransaction(attackerTxnRequest)
    }
  }

  @Test
  fun `test a caller can read a SEP-31 receiver via transaction_id even when it was registered under a different memo`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val callerJwt = authenticateWithMemo(sharedKeyPair, 1UL)
    val otherMemoJwt = authenticateWithMemo(sharedKeyPair, 2UL)

    val callerSep12Client = Sep12Client(toml.getString("KYC_SERVER"), callerJwt)
    val otherMemoSep12Client = Sep12Client(toml.getString("KYC_SERVER"), otherMemoJwt)
    val callerSep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), callerJwt)
    val callerSep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), callerJwt)

    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = otherMemoSep12Client.putCustomer(receiverCustomerRequest)!!

    val quote =
      callerSep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val txnRequest = gson.fromJson(postTxnRequestTemplate, Sep31PostTransactionRequest::class.java)
    txnRequest.receiverId = receiverCustomer.id
    txnRequest.quoteId = quote.id

    // Same account, different memo: SEP-31 allows this (ignoreMemo=true for sender_id/receiver_id
    // ownership), since the caller's client legitimately manages both memos' customer records.
    val txn = callerSep31Client.postTransaction(txnRequest)
    assertNotNull(txn.id)

    // Reading the receiver's KYC data back via transaction_id must not be stricter than the
    // check that allowed the transaction to be created in the first place -- both go through the
    // same ignoreMemo=true relaxation for a SEP-31 sender_id/receiver_id.
    val fetchedCustomer =
      callerSep12Client.getCustomer(transactionId = txn.id, type = "sep31-receiver")
    assertEquals(receiverCustomer.id, fetchedCustomer?.id)
  }
}

private const val sep24DepositWithKycFieldsJson =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "lang": "en",
    "first_name": "Alice",
    "last_name": "Victim",
    "email_address": "alice-victim@example.com"
}"""

private const val postTxnRequestTemplate =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "funding_method": "SEPA",
    "fields": {
        "transaction": {
            "receiver_routing_number": "r0123",
            "receiver_account_number": "a0456",
            "type": "SWIFT"
        }
    }
}"""
