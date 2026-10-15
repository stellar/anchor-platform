package org.stellar.anchor.platform.readiness

import io.ktor.http.Url
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.reference.client.AnchorReferenceServerClient
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

/**
 * Proves, before the essential tests start, that an event the platform publishes reaches the
 * reference server. When delivery is lost every end-to-end test that waits for events fails one by
 * one (up to 72 minutes in CI); this probe fails the job in two minutes instead, with a message
 * that names the cause. A SEP-24 deposit's creation event is used because the reference server's
 * SEP-24 processor ignores it, so the probe changes nothing another test observes.
 */
class EventDeliveryProbeTest : IntegrationTestBase(TestConfig()) {
  private val referenceServer =
    AnchorReferenceServerClient(Url(config.env["reference.server.url"]!!))

  @Test
  fun `an event the platform publishes reaches the reference server`() {
    val jwt = runBlocking { anchor.auth().authenticate(SigningKeyPair(KeyPair.random())) }.token
    val transactionId =
      Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), jwt)
        .deposit(mapOf("asset_code" to "USDC", "asset_issuer" to USDC_ISSUER, "amount" to "1"))
        .id

    val deadline = System.nanoTime() + TIMEOUT.toNanos()
    while (runBlocking { referenceServer.getEvents(transactionId) }.isEmpty()) {
      if (System.nanoTime() > deadline) {
        fail<Unit>(
          "event delivery is not ready: no event for transaction $transactionId reached the " +
            "reference server within ${TIMEOUT.seconds} s"
        )
      }
      Thread.sleep(POLL_INTERVAL.toMillis())
    }
  }

  private companion object {
    const val USDC_ISSUER = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val TIMEOUT: Duration = Duration.ofSeconds(120)
    val POLL_INTERVAL: Duration = Duration.ofSeconds(2)
  }
}
