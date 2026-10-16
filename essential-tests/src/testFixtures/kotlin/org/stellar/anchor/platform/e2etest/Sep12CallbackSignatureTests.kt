package org.stellar.anchor.platform.e2etest

import io.ktor.http.*
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.assertCallbackSignatures
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.walletsdk.anchor.auth

/**
 * The callbacks AP sends to a client's `callback_urls.sep12` carry a signature a wallet can verify
 * with the `SIGNING_KEY` of the TOML. AP has no `PUT /customer/callback`: the URL comes from the
 * client's configuration (`referenceCustodial`, selected by the token's account).
 */
class Sep12CallbackSignatureTests : IntegrationTestBase(TestConfig()) {
  private val gson = GsonUtils.getInstance()
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))

  // The custodial client `referenceCustodial` owns the wallet account's signing key and has a
  // `callback_urls.sep12`; a plain keypair has no client and receives no callbacks. A random memo
  // keeps this customer apart from the others of the shared wallet account.
  private fun authenticateAsReferenceCustodialClient(): String =
    runBlocking {
        anchor
          .auth()
          .authenticate(walletKeyPair, memoId = Random.nextLong(1, Long.MAX_VALUE).toULong())
      }
      .token

  @Test
  fun `test every SEP-12 callback is signed with the anchor's key`() = runBlocking {
    val notBefore = System.currentTimeMillis() / 1000
    val jwt = authenticateAsReferenceCustodialClient()
    val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), jwt)
    val request = gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    request.type = "sep24"
    val customerId = sep12Client.putCustomer(request)!!.id

    val rawCallbacks = walletServerClient.pollRawCustomerCallbacks(customerId, 1)

    assertTrue(rawCallbacks.isNotEmpty()) {
      "no SEP-12 callback reached the wallet server for customer $customerId"
    }
    // SEP12CB-01: the callback reports the customer and the status GET /customer reports.
    val current = sep12Client.getCustomer(customerId, "sep24")!!
    rawCallbacks.forEach { raw ->
      val callback = gson.fromJson(raw.body, Sep12GetCustomerResponse::class.java)
      assertEquals(customerId, callback.id)
      assertEquals(current.status, callback.status)
    }
    // SEP12CB-02..05: header shape, signature over <t>.<host>.<body>, timestamp window, tamper
    // check.
    assertCallbackSignatures(
      rawCallbacks,
      toml.getString("SIGNING_KEY"),
      config.env["wallet.hostname"],
      1,
      notBefore,
    )
  }
}
