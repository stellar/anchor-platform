package org.stellar.anchor.platform.event

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.spyk
import io.mockk.verify
import java.util.*
import org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG
import org.apache.kafka.common.config.SaslConfigs.SASL_MECHANISM
import org.apache.kafka.common.config.SslConfigs.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
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
  private lateinit var kafkaSession: KafkaSession

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    kafkaSession = spyk(KafkaSession(kafkaConfig, "test", eventQueue))
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
