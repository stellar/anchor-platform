package org.stellar.anchor.platform.event

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.spyk
import io.mockk.verify
import java.util.*
import org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SaslConfigs.SASL_MECHANISM
import org.apache.kafka.common.config.SslConfigs.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ConfigurableApplicationContext
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.exception.AnchorException
import org.stellar.anchor.event.EventService.EventQueue
import org.stellar.anchor.platform.config.KafkaConfig
import org.stellar.anchor.platform.config.KafkaConfig.SecurityProtocol.PLAINTEXT
import org.stellar.anchor.platform.config.PropertySecretConfig.SECRET_EVENTS_QUEUE_KAFKA_PASSWORD
import org.stellar.anchor.platform.config.PropertySecretConfig.SECRET_EVENTS_QUEUE_KAFKA_USERNAME
import org.stellar.anchor.platform.configurator.SecretManager
import org.stellar.anchor.platform.utils.ResourceHelper
import org.stellar.anchor.platform.utils.TrustAllSslEngineFactory

@ExtendWith(LockAndMockTest::class)
class KafkaSessionTest {
  companion object {
    const val TEST_USERNAME = "user"
    const val TEST_PASSWORD = "password"
  }

  @MockK(relaxed = true) lateinit var kafkaConfig: KafkaConfig
  @MockK(relaxed = true) lateinit var eventQueue: EventQueue
  @MockK(relaxed = true) lateinit var appContext: ConfigurableApplicationContext
  private lateinit var kafkaSession: KafkaSession

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    kafkaSession = spyk(KafkaSession(kafkaConfig, "test", eventQueue))
  }

  private val partition0 = TopicPartition("TRANSACTION", 0)
  private val partition1 = TopicPartition("TRANSACTION", 1)

  private fun sessionWithConsumer(vararg partitions: TopicPartition): MockConsumer<String, String> {
    val consumer = MockConsumer<String, String>(OffsetResetStrategy.EARLIEST)
    consumer.assign(partitions.toList())
    consumer.updateBeginningOffsets(partitions.associateWith { 0L })
    kafkaSession.consumer = consumer
    return consumer
  }

  private fun eventRecord(partition: TopicPartition, offset: Long) =
    ConsumerRecord(
      partition.topic(),
      partition.partition(),
      offset,
      "k$offset",
      "{\"type\":\"transaction_created\",\"id\":\"event-$offset\"}",
    )

  private fun malformedRecord(partition: TopicPartition, offset: Long) =
    ConsumerRecord(partition.topic(), partition.partition(), offset, "k$offset", "{\"type\":")

  private fun MockConsumer<String, String>.committedOffset(partition: TopicPartition): Long? =
    committed(setOf(partition))[partition]?.offset()

  @Test
  fun `ack of the second event commits the offset after that record`() {
    val consumer = sessionWithConsumer(partition0)
    listOf(10L, 11L, 12L).forEach { consumer.addRecord(eventRecord(partition0, it)) }
    val response = kafkaSession.read()

    kafkaSession.ack(response, 2)

    assertEquals(12L, consumer.committedOffset(partition0))
  }

  @Test
  fun `ack of zero events commits nothing`() {
    val consumer = sessionWithConsumer(partition0)
    consumer.addRecord(eventRecord(partition0, 10L))
    val response = kafkaSession.read()

    kafkaSession.ack(response, 0)

    assertEquals(null, consumer.committedOffset(partition0))
  }

  @Test
  fun `ack commits each event on its own partition and offset`() {
    val consumer = sessionWithConsumer(partition0, partition1)
    consumer.addRecord(eventRecord(partition0, 5L))
    consumer.addRecord(eventRecord(partition1, 7L))
    consumer.addRecord(eventRecord(partition0, 6L))
    consumer.addRecord(eventRecord(partition1, 8L))
    val response = kafkaSession.read()

    // A poll returns the records grouped by partition, in no fixed partition order, so the
    // expectation is derived from the order of the events actually returned (offset = event id).
    val offsets = response.events.map { it.id.removePrefix("event-").toLong() }
    assertEquals(setOf(5L, 6L, 7L, 8L), offsets.toSet())
    for (handled in 1..offsets.size) {
      kafkaSession.ack(response, handled)
      val handledOffsets = offsets.take(handled)
      assertEquals(
        handledOffsets.filter { it < 7L }.maxOrNull()?.plus(1),
        consumer.committedOffset(partition0),
      )
      assertEquals(
        handledOffsets.filter { it >= 7L }.maxOrNull()?.plus(1),
        consumer.committedOffset(partition1),
      )
    }
  }

  @Test
  fun `a malformed record does not shift the offset committed for a handled event`() {
    val consumer = sessionWithConsumer(partition0)
    consumer.addRecord(eventRecord(partition0, 0L))
    consumer.addRecord(malformedRecord(partition0, 1L))
    consumer.addRecord(eventRecord(partition0, 2L))
    val response = kafkaSession.read()
    assertEquals(2, response.events.size)

    kafkaSession.ack(response, 1)
    assertEquals(1L, consumer.committedOffset(partition0))

    kafkaSession.ack(response, 2)
    assertEquals(3L, consumer.committedOffset(partition0))
  }

  @Test
  fun `ack of the whole batch commits the position after the last record`() {
    val consumer = sessionWithConsumer(partition0)
    listOf(10L, 11L, 12L).forEach { consumer.addRecord(eventRecord(partition0, it)) }
    val response = kafkaSession.read()

    kafkaSession.ack(response)

    assertEquals(13L, consumer.committedOffset(partition0))
  }

  @Test
  fun `keepAlive before any read does nothing`() {
    kafkaSession.keepAlive()
  }

  @Test
  fun `keepAlive with no assigned partitions does nothing`() {
    val consumer = MockConsumer<String, String>(OffsetResetStrategy.EARLIEST)
    kafkaSession.consumer = consumer

    kafkaSession.keepAlive()

    assertEquals(emptySet<TopicPartition>(), consumer.paused())
  }

  @Test
  fun `keepAlive pauses the assignment and hands no record to the caller`() {
    val consumer = sessionWithConsumer(partition0, partition1)
    consumer.addRecord(eventRecord(partition0, 10L))

    kafkaSession.keepAlive()

    assertEquals(setOf(partition0, partition1), consumer.paused())
    assertEquals(null, consumer.committedOffset(partition0))
  }

  @Test
  fun `read after keepAlive resumes the partitions and returns the waiting record`() {
    val consumer = sessionWithConsumer(partition0)
    consumer.addRecord(eventRecord(partition0, 10L))
    kafkaSession.keepAlive()

    val response = kafkaSession.read()

    assertEquals(listOf("event-10"), response.events.map { it.id })
    assertEquals(emptySet<TopicPartition>(), consumer.paused())
  }

  @Test
  fun `keepAlive throws instead of dropping a record the poll returns`() {
    val consumer = sessionWithConsumer(partition0)
    // A rebalance clears the pause: the partition is live again when the keep-alive poll runs.
    consumer.schedulePollTask {
      consumer.resume(setOf(partition0))
      consumer.addRecord(eventRecord(partition0, 10L))
    }

    assertThrows(AnchorException::class.java) { kafkaSession.keepAlive() }

    // Nothing was committed, so a replacement session reads the record again.
    assertEquals(null, consumer.committedOffset(partition0))
  }

  @Test
  fun `consumer config sets max poll records to 50`() {
    every { kafkaConfig.bootstrapServer } returns "localhost:9092"
    every { kafkaConfig.securityProtocol } returns PLAINTEXT
    val props = kafkaSession.createConsumerConfig()
    assertEquals(50, props[ConsumerConfig.MAX_POLL_RECORDS_CONFIG])
  }

  @Test
  fun `consumer config sets max poll interval to 15 minutes`() {
    every { kafkaConfig.bootstrapServer } returns "localhost:9092"
    every { kafkaConfig.securityProtocol } returns PLAINTEXT
    val props = kafkaSession.createConsumerConfig()
    assertEquals(900000, props[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG])
  }

  @Test
  fun `test security-protocol PLAINTEXT `() {
    val properties = Properties()
    every { kafkaConfig.securityProtocol } returns PLAINTEXT
    kafkaSession.configureAuth(properties)
    assert(properties.size == 0)
  }

  @Test
  @LockAndMockStatic([SecretManager::class])
  fun `test security-protocol SASL_PLAINTEXT `() {
    val properties = Properties()
    every { kafkaConfig.securityProtocol } returns KafkaConfig.SecurityProtocol.SASL_PLAINTEXT
    every { kafkaConfig.saslMechanism } returns KafkaConfig.SaslMechanism.PLAIN

    every { SecretManager.secret(SECRET_EVENTS_QUEUE_KAFKA_USERNAME) } returns TEST_USERNAME
    every { SecretManager.secret(SECRET_EVENTS_QUEUE_KAFKA_PASSWORD) } returns TEST_PASSWORD

    kafkaSession.configureAuth(properties)
    assert(properties.size == 3)
    assert(properties.getProperty("security.protocol") == "SASL_PLAINTEXT")
    assert(properties.getProperty("sasl.mechanism") == "PLAIN")
    assert(
      properties.getProperty("sasl.jaas.config") ==
        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$TEST_USERNAME\" password=\"$TEST_PASSWORD\";"
    )
  }

  @LockAndMockStatic([SecretManager::class, ResourceHelper::class])
  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `test security-protocol SASL_SSL `(sslVerifyCert: Boolean) {
    val properties = Properties()
    every { kafkaConfig.securityProtocol } returns KafkaConfig.SecurityProtocol.SASL_SSL
    every { kafkaConfig.saslMechanism } returns KafkaConfig.SaslMechanism.PLAIN
    every { kafkaConfig.sslVerifyCert } returns sslVerifyCert
    kafkaSession.sslKeystoreLocation = "keystore.jks"
    kafkaSession.sslTruststoreLocation = "truststore.jks"

    every { SecretManager.secret(SECRET_EVENTS_QUEUE_KAFKA_USERNAME) } returns TEST_USERNAME
    every { SecretManager.secret(SECRET_EVENTS_QUEUE_KAFKA_PASSWORD) } returns TEST_PASSWORD

    kafkaSession.configureAuth(properties)

    assert(properties.size == 5)
    assert(
      properties.getProperty("sasl.jaas.config") ==
        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$TEST_USERNAME\" password=\"$TEST_PASSWORD\";"
    )
    assertEquals("SASL_SSL", properties.getProperty(SECURITY_PROTOCOL_CONFIG))
    assertEquals("PLAIN", properties.getProperty(SASL_MECHANISM))

    if (sslVerifyCert) {
      assertEquals("keystore.jks", properties.getProperty(SSL_KEYSTORE_LOCATION_CONFIG))
      assertEquals("truststore.jks", properties.getProperty(SSL_TRUSTSTORE_LOCATION_CONFIG))
    } else {
      assertEquals("", properties.getProperty(SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG))
      val cls = properties.get(SSL_ENGINE_FACTORY_CLASS_CONFIG) as Class<TrustAllSslEngineFactory>
      assertEquals("org.stellar.anchor.platform.utils.TrustAllSslEngineFactory", cls.name)

      // make sure we don't look for keystore and truststore
      verify(exactly = 0) { kafkaSession.find(any()) }
    }
  }
}
