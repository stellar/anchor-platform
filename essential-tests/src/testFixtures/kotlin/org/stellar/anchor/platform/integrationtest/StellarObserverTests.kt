package org.stellar.anchor.platform.integrationtest

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson
import org.stellar.anchor.util.StringHelper.isNotEmpty

class StellarObserverTests : IntegrationTestBase(TestConfig()) {
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
    assertEquals(200, response.code)

    val responseBody = gson.fromJson(response.body!!.string(), HashMap::class.java)
    assertEquals(5, responseBody.size)
    assertNotNull(responseBody["started_at"])
    assertNotNull(responseBody["elapsed_time_ms"])
    assertNotNull(responseBody["number_of_checks"])
    assertEquals(2L, responseBody["number_of_checks"])
    assertNotNull(responseBody["version"])
    assertNotNull(responseBody["checks"])

    val checks = responseBody["checks"] as Map<*, *>

    assertEquals(2, checks.size)
    assertNotNull(checks["config"])

    if (isNotEmpty(this.config.env["stellar_network.rpc_url"])) {
      val stellarPaymentObserverCheck = checks["stellar_rpc_payment_observer"] as Map<*, *>
      assertNotNull(stellarPaymentObserverCheck)
      assertEquals(stellarPaymentObserverCheck["status"], "GREEN")
      // TODO: Check the streams after unified event observer is implemented.
    } else {
      val stellarPaymentObserverCheck = checks["horizon_payment_observer"] as Map<*, *>
      assertTrue((stellarPaymentObserverCheck["status"] as String) in setOf("GREEN", "YELLOW"))

      val observerStreams = stellarPaymentObserverCheck["streams"] as List<*>
      assertEquals(1, observerStreams.size)

      val stream1 = observerStreams[0] as Map<*, *>
      assertEquals(5, stream1.size)
      assertEquals(false, stream1["thread_shutdown"])
      assertEquals(false, stream1["thread_terminated"])
      assertEquals(false, stream1["stopped"])
      assertNotNull(stream1["last_event_id"])
    }
  }
}
