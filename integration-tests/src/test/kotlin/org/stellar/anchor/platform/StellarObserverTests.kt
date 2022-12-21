package org.stellar.anchor.platform

import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions

class StellarObserverTests {
  companion object {
    private val httpClient: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    fun setup() {}

    fun testStellarObserverHealth() {
      val httpRequest =
        Request.Builder()
          .url(
            "http://localhost:${AnchorPlatformIntegrationTest.OBSERVER_HEALTH_SERVER_PORT}/health"
          )
          .header("Content-Type", "application/json")
          .get()
          .build()
      val response = httpClient.newCall(httpRequest).execute()
      Assertions.assertEquals(200, response.code)

      val responseBody = gson.fromJson(response.body!!.string(), HashMap::class.java)
      Assertions.assertEquals(5, responseBody.size)
      Assertions.assertNotNull(responseBody["started_at"])
      Assertions.assertNotNull(responseBody["elapsed_time_ms"])
      Assertions.assertNotNull(responseBody["number_of_checks"])
      Assertions.assertEquals(2.0, responseBody["number_of_checks"])
      Assertions.assertNotNull(responseBody["version"])
      Assertions.assertNotNull(responseBody["checks"])

      val checks = responseBody["checks"] as Map<*, *>

      Assertions.assertEquals(2, checks.size)
      Assertions.assertNotNull(checks["config"])
      Assertions.assertNotNull(checks["stellar_payment_observer"])

      val stellarPaymentObserverCheck = checks["stellar_payment_observer"] as Map<*, *>
      Assertions.assertEquals(2, stellarPaymentObserverCheck.size)
      Assertions.assertEquals("GREEN", stellarPaymentObserverCheck["status"])

      val observerStreams = stellarPaymentObserverCheck["streams"] as List<*>
      Assertions.assertEquals(1, observerStreams.size)

      val stream1 = observerStreams[0] as Map<*, *>
      Assertions.assertEquals(5, stream1.size)
      Assertions.assertEquals(false, stream1["thread_shutdown"])
      Assertions.assertEquals(false, stream1["thread_terminated"])
      Assertions.assertEquals(false, stream1["stopped"])
      Assertions.assertNotNull(stream1["last_event_id"])
    }
  }
}

fun stellarObserverTestAll() {
  StellarObserverTests.setup()

  println("Performing Stellar observer tests...")
  StellarObserverTests.setup()
}
