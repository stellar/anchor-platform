package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.Test
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.config.PropertyEventConfig
import org.stellar.anchor.platform.event.DefaultEventService
import org.stellar.anchor.util.GsonUtils

class EventProcessingServerTests : IntegrationTestBase(TestConfig()) {
  companion object {
    val eventConfig =
      GsonUtils.getInstance().fromJson(eventConfigJson, PropertyEventConfig::class.java)!!
  }

  @Test
  fun testOk() {
    val eventService = DefaultEventService(eventConfig)
    val session = eventService.createSession("testOk", TRANSACTION)
    val quoteEvent = GsonUtils.getInstance().fromJson(testQuoteEvent, AnchorEvent::class.java)

    // TODO: this test doesn't fail when the event is not published
    session.publish(quoteEvent)
  }
}

val testQuoteEvent =
  """
  {
    "type": "QUOTE_CREATED",
    "id": "123",
    "sep": "38",
    "quote": {
      "id": "QUOTE-ID-123",
      "sell_amount": "103",
      "sell_asset": "USDC",
      "buy_amount": "100",
      "buy_asset": "USD",
      "expires_at": "2021-01-01T00:00:00Z",
      "price": "1.02",
      "total_price": "1.03",
      "creator": {
        "id": "CREATOR-ID-1234",
        "account": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "created_at": "2021-01-01T00:00:00Z",
      "fee": {
        "total": "0.01",
        "asset": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      }
    }
  }

"""
    .trimIndent()

val eventConfigJson =
  """
  {
    "enabled": true,
    "queue": {
      "type": "KAFKA",
      "kafka": {
        "bootstrapServer": "kafka:29092",
        "clientId": "testOk",
        "retries": 0,
        "lingerMs": 1000,
        "batchSize": 10,
        "pollTimeoutSeconds": 10,
        "securityProtocol": "PLAINTEXT"
      }
    }
  }
"""
    .trimIndent()
