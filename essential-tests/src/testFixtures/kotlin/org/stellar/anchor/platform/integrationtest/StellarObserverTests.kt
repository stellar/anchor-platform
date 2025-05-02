package org.stellar.anchor.platform.integrationtest

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson

class StellarObserverTests : AbstractIntegrationTests(TestConfig()) {
  companion object {
    const val OBSERVER_HEALTH_SERVER_PORT = 8083
  }

  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()

  @Test
  fun testStellarObserverHealth() {
    val httpRequest =
      Request.Builder()
        .url("http://localhost:$OBSERVER_HEALTH_SERVER_PORT/health")
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
    Assertions.assertEquals(2L, responseBody["number_of_checks"])
    Assertions.assertNotNull(responseBody["version"])
    Assertions.assertNotNull(responseBody["checks"])

    val checks = responseBody["checks"] as Map<*, *>

    Assertions.assertEquals(2, checks.size)
    Assertions.assertNotNull(checks["config"])
    Assertions.assertNotNull(checks["horizon_payment_observer"])

    val stellarPaymentObserverCheck = checks["horizon_payment_observer"] as Map<*, *>
    Assertions.assertEquals(2, stellarPaymentObserverCheck.size)
    Assertions.assertTrue(
      (stellarPaymentObserverCheck["status"] as String) in setOf("GREEN", "YELLOW")
    )

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
