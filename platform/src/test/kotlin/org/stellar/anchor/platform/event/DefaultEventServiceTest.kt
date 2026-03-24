package org.stellar.anchor.platform.event

import org.apache.commons.lang3.NotImplementedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.config.event.QueueConfig.QueueType.*
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.platform.config.PropertyEventConfig
import org.stellar.anchor.util.GsonUtils

internal class DefaultEventServiceTest {
  private lateinit var eventConfig: PropertyEventConfig

  @BeforeEach
  fun setUp() {
    eventConfig =
      GsonUtils.getInstance().fromJson(eventConfigJson, PropertyEventConfig::class.java)!!
  }

  @Test
  fun `test if the create session returns correct Session class`() {
    // Test create Kafka session
    eventConfig.queue.type = KAFKA
    var defaultEventService = DefaultEventService(eventConfig)
    var session = defaultEventService.createSession("test", TRANSACTION)
    assert(session is KafkaSession)
    var kafkaSession: KafkaSession = session as KafkaSession
    assertEquals(kafkaSession.topic, "TRANSACTION")

    // Test create SQS session should throw not implemented exception
    eventConfig.queue.type = SQS
    assertThrows<NotImplementedException> { defaultEventService.createSession("test", TRANSACTION) }
    // Test create MSK session should throw not implemented exception
    eventConfig.queue.type = MSK
    assertThrows<NotImplementedException> { defaultEventService.createSession("test", TRANSACTION) }
  }

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
        "pollTimeoutSeconds": 10
      }
    }
  }
"""
}
