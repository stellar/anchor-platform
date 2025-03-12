package org.stellar.anchor.platform.integrationtest

import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.sdk.Server
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.operations.PaymentOperationResponse

open class PlatformAPITestBase(config: TestConfig) : IntegrationTestBase(config) {
  companion object {
    const val JSON_RPC_VERSION = "2.0"

    // the search and replace values are injected into the expected values
    const val TX_ID_KEY = "%TX_ID%"
    const val RECEIVER_ID_KEY = "%RECEIVER_ID%"
    const val SENDER_ID_KEY = "%SENDER_ID%"

    // the test payment values
    const val TEST_PAYMENT_MEMO = "22bf7341574e4b1082516a2e84a8"
    const val TEST_PAYMENT_DEST_ACCOUNT = "GBDYDBJKQBJK4GY4V7FAONSFF2IBJSKNTBYJ65F5KCGBY2BIGPGGLJOH"
    const val TEST_PAYMENT_ASSET_CIRCLE_USDC =
      "USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

    // custody deposit address
    const val CUSTODY_DEST_ACCOUNT = "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O"

    lateinit var testPaymentValues: List<Pair<String, String>>

    fun inject(target: String, vararg replacements: Pair<String, String>): String {
      var result = target
      for ((search, replace) in replacements) {
        result = result.replace(search, replace)
      }

      for ((search, replace) in testPaymentValues) {
        result = result.replace(search, replace)
      }

      return result.trimIndent()
    }

    // fetch the test payment from the testnet
    fun fetchTestPayment() {
      val destAccount = TEST_PAYMENT_DEST_ACCOUNT
      val horizonServer = Server("https://horizon-testnet.stellar.org")
      val payments =
        horizonServer
          .payments()
          .forAccount(destAccount)
          .order(RequestBuilder.Order.DESC)
          .includeTransactions(true)
          .limit(100)
          .execute()
          .records
      for (payment in payments) {
        if (payment is PaymentOperationResponse) {
          if (payment.transaction.memo.toString() == TEST_PAYMENT_MEMO) {
            println("Found test payment")
            // initialize the test payment value pairs for injection
            testPaymentValues =
              listOf(
                Pair("%TESTPAYMENT_ID%", payment.id.toString()),
                Pair("%TESTPAYMENT_AMOUNT%", payment.amount),
                Pair("%TESTPAYMENT_TXN_HASH%", payment.transactionHash),
                Pair("%TESTPAYMENT_SRC_ACCOUNT%", payment.from),
                Pair("%TESTPAYMENT_DEST_ACCOUNT%", payment.to),
                Pair("%TESTPAYMENT_ASSET_CIRCLE_USDC%", TEST_PAYMENT_ASSET_CIRCLE_USDC),
                Pair("%CUSTODY_DEST_ACCOUNT%", CUSTODY_DEST_ACCOUNT),
              )

            return
          }
        }
      }

      println("\n*** STOP ***")
      println("Cannot find test payment")
      println(
        "Please visit the testnet reset script: https://github.com/stellar/useful-scripts to create the test payment"
      )
      throw Exception("Cannot find test payment")
    }
  }

  init {
    // fetch the test payment
    fetchTestPayment()
  }
}

fun String.inject(search: String, replace: String): String {
  return PlatformAPITestBase.inject(this, search to replace)
}
