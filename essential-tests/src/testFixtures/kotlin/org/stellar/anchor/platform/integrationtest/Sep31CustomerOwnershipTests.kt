package org.stellar.anchor.platform.integrationtest

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
import org.stellar.walletsdk.horizon.SigningKeyPair

class Sep31CustomerOwnershipTests : IntegrationTestBase(TestConfig()) {
  private val sep12Client: Sep12Client = Sep12Client(toml.getString("KYC_SERVER"), this.token.token)
  private val sep31Client: Sep31Client =
    Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), this.token.token)
  private val sep38Client: Sep38Client =
    Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), this.token.token)

  private fun authenticateNewIdentity(): String {
    val keyPair = SigningKeyPair(KeyPair.random())
    return runBlocking { anchor.auth().authenticate(keyPair) }.token
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

  // Per ANCHOR-1279's audit: sender_id/receiver_id are never validated against a real SEP-12
  // customer record when the asset doesn't advertise sep12.<role> (KYC not required for that
  // role) — a receiver_id claim only checks that no *other* caller has claimed it before, not
  // that it corresponds to a customer that actually exists. This is spec-compliant (SEP-31 only
  // requires the id correspond to a real customer when the anchor requires SEP-12 KYC for that
  // role), so this test locks in the current, intentional behavior rather than treating it as a
  // bug — see the essential-tests asset config (no `sep12:` block on the USDC/JPYC test assets).
  @Test
  fun `test caller can claim a receiver_id with no SEP-12 customer record when KYC is not required`() {
    val neverRegisteredReceiverId = java.util.UUID.randomUUID().toString()

    val txn = sep31Client.postTransaction(mkTxnRequest(neverRegisteredReceiverId))

    assertNotNull(txn.id)
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
}

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
