package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.config.event.QueueConfig.QueueType
import org.stellar.anchor.platform.config.KafkaConfig.SaslMechanism.PLAIN
import org.stellar.anchor.platform.config.KafkaConfig.SecurityProtocol.PLAINTEXT
import org.stellar.anchor.platform.config.KafkaConfig.SecurityProtocol.SASL_SSL

class PropertyQueueConfigTest {
  private lateinit var configs: PropertyQueueConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    configs = PropertyQueueConfig()
    configs.type = QueueType.KAFKA
    configs.kafka = KafkaConfig()
    configs.kafka.bootstrapServer = "localhost:9092"
    configs.kafka.retries = 0
    configs.kafka.lingerMs = 100
    configs.kafka.batchSize = 10
    configs.kafka.pollTimeoutSeconds = 1
    configs.kafka.securityProtocol = PLAINTEXT

    errors = BindException(configs, "config")
  }

  @Test
  fun `test invalid null type`() {
    configs.type = null
    configs.validate(configs, errors)

    Assertions.assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "queue-type-empty")
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty bootstrap servers`() {
    configs.kafka.bootstrapServer = null
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-bootstrap-server-empty")
  }

  @Test
  fun `test bad retries`() {
    configs.kafka.retries = -1
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-retries-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, -100])
  fun `test bad linger ms`(lingerMs: Int) {
    configs.kafka.lingerMs = lingerMs
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-linger-ms-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, -1])
  fun `test bad batch size`(batchSize: Int) {
    configs.kafka.batchSize = batchSize
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-batch-size-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, -1])
  fun `test bad poll timeout`() {
    configs.kafka.pollTimeoutSeconds = -1
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-poll-timeout-seconds-invalid")
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  @NullSource
  fun `test invalid keystore`(sslKeystoreLocation: String?) {
    configs.kafka.saslMechanism = PLAIN
    configs.kafka.securityProtocol = SASL_SSL
    configs.kafka.sslKeystoreLocation = sslKeystoreLocation
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-ssl-keystore-location-empty")
  }

  @Test
  fun `test keystore not found()`() {
    configs.kafka.saslMechanism = PLAIN
    configs.kafka.securityProtocol = SASL_SSL
    configs.kafka.sslKeystoreLocation = "non-existent-keystore.jks"
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, "kafka-ssl-keystore-location-not-found")
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  @NullSource
  fun `test invalid truststore`(sslTruststoreLocation: String?) {
    configs.kafka.saslMechanism = PLAIN
    configs.kafka.securityProtocol = SASL_SSL
    configs.kafka.sslKeystoreLocation = "classpath:secrets/keystore.jks"
    configs.kafka.sslTruststoreLocation = sslTruststoreLocation
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, 0, "kafka-ssl-truststore-location-empty")
  }

  @Test
  fun `test truststore not found()`() {
    configs.kafka.saslMechanism = PLAIN
    configs.kafka.securityProtocol = SASL_SSL
    configs.kafka.sslKeystoreLocation = "classpath:secrets/keystore.jks"
    configs.kafka.sslTruststoreLocation = "non-existent-truststore.jks"
    configs.validate(configs, errors)

    assertTrue(1 <= errors.errorCount)
    assertErrorCode(errors, 0, "kafka-ssl-truststore-location-not-found")
  }
}
