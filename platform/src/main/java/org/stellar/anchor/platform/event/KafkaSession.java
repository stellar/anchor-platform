package org.stellar.anchor.platform.event;

import static org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.*;
import static org.apache.kafka.common.config.SaslConfigs.SASL_MECHANISM;
import static org.apache.kafka.common.config.SslConfigs.*;
import static org.stellar.anchor.platform.config.PropertySecretConfig.*;
import static org.stellar.anchor.platform.configurator.SecretManager.*;
import static org.stellar.anchor.platform.utils.ResourceHelper.*;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import com.google.gson.JsonSyntaxException;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import lombok.AllArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.EventPublishException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.event.EventService.EventQueue;
import org.stellar.anchor.platform.config.KafkaConfig;
import org.stellar.anchor.platform.utils.TrustAllSslEngineFactory;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;

public class KafkaSession implements EventService.Session {
  // The most records one poll returns, which bounds the events at risk if a commit fails.
  static final int MAX_POLL_RECORDS = 50;
  // The longest the consumer may go without polling before Kafka removes it from its group. It
  // is above the 10-minute HTTP timeouts of the callback handlers, so one blocking callback between
  // two keep-alive polls cannot evict the consumer.
  static final int MAX_POLL_INTERVAL_MS = 900_000;

  final KafkaConfig kafkaConfig;
  final String sessionName;
  final String topic;
  Producer<String, String> producer = null;
  Consumer<String, String> consumer = null;
  String sslKeystoreLocation;
  String sslTruststoreLocation;

  public KafkaSession(KafkaConfig kafkaConfig, String sessionName, EventQueue queue)
      throws IOException {
    this.kafkaConfig = kafkaConfig;
    this.sessionName = sessionName;
    this.topic = queue.name();

    if (kafkaConfig.getSslVerifyCert()) {
      if (kafkaConfig.getSecurityProtocol() == KafkaConfig.SecurityProtocol.SASL_SSL) {
        // If the keystore and truststore files exist, use them, otherwise, use the resources
        sslKeystoreLocation = find(kafkaConfig.getSslKeystoreLocation());
        sslTruststoreLocation = find(kafkaConfig.getSslTruststoreLocation());
      }
    }
  }

  @Override
  public void publish(AnchorEvent event) throws AnchorException {
    try {
      if (producer == null) {
        producer = createProducer();
      }
      String serialized = GsonUtils.getInstance().toJson(event);
      ProducerRecord<String, String> record =
          new ProducerRecord<>(topic, event.getId(), serialized);
      record.headers().add(new RecordHeader("type", event.getType().type.getBytes()));
      // If the queue is offline, throw an exception
      try {
        producer.send(record).get();
      } catch (Exception ex) {
        throw new EventPublishException("Failed to publish event to Kafka.", ex);
      }

      // publish the event to the metrics
      Metrics.counter(
              "event.published",
              "class",
              event.getClass().getSimpleName(),
              "type",
              event.getType().type)
          .increment();

    } catch (Exception ex) {
      Log.errorEx(ex);
    }
  }

  @Override
  public EventService.ReadResponse read() throws AnchorException {
    if (consumer == null) {
      consumer = createConsumer();
      consumer.subscribe(java.util.Collections.singletonList(topic));
    }

    ConsumerRecords<String, String> consumerRecords =
        consumer.poll(Duration.ofSeconds(kafkaConfig.getPollTimeoutSeconds()));
    ArrayList<AnchorEvent> events = new ArrayList<>(consumerRecords.count());
    ArrayList<ConsumerRecord<String, String>> eventRecords =
        new ArrayList<>(consumerRecords.count());
    if (consumerRecords.isEmpty()) {
      debugF("Received {} Kafka records", consumerRecords.count());
    } else {
      Log.infoF("Received {} Kafka records", consumerRecords.count());
      for (ConsumerRecord<String, String> record : consumerRecords) {
        try {
          AnchorEvent deserialized =
              GsonUtils.getInstance().fromJson(record.value(), AnchorEvent.class);
          if (deserialized.getType() == null) {
            throw new EventPublishException("null event type");
          }
          events.add(deserialized);
          eventRecords.add(record);
        } catch (JsonSyntaxException | AnchorException ex) {
          Log.debugF(
              "Skipping mal-formatted event from Kafka. ex={}, message={}", ex, ex.getMessage());
        }
      }
      // TOOD: emit metrics here.
    }
    return new KafkaReadResponse(events, eventRecords);
  }

  @AllArgsConstructor
  public static class KafkaReadResponse implements EventService.ReadResponse {
    private final List<AnchorEvent> events;
    // The record each event was built from, in the same order as the events.
    private final List<ConsumerRecord<String, String>> records;

    @Override
    public List<AnchorEvent> getEvents() {
      return events;
    }
  }

  @Override
  public void ack(EventService.ReadResponse readResponse) throws AnchorException {
    if (consumer != null) {
      consumer.commitSync();
    }
  }

  @Override
  public void ack(EventService.ReadResponse readResponse, int handledCount) throws AnchorException {
    List<ConsumerRecord<String, String>> records = ((KafkaReadResponse) readResponse).records;
    if (consumer == null || handledCount <= 0 || records.isEmpty()) {
      return;
    }
    // Events are handled in the order they were read, so committing the offset after the last
    // handled record of its own partition never skips a record that was not handled.
    ConsumerRecord<String, String> last = records.get(Math.min(handledCount, records.size()) - 1);
    consumer.commitSync(
        Map.of(
            new TopicPartition(last.topic(), last.partition()),
            new OffsetAndMetadata(last.offset() + 1)));
  }

  @Override
  public void close() {
    if (producer != null) {
      producer.close();
    }
    if (consumer != null) {
      consumer.close();
    }
  }

  @Override
  public String getSessionName() {
    return sessionName;
  }

  public void testConnection() throws Exception {
    Properties props = createProducerConfig();
    try (AdminClient adminClient = AdminClient.create(props)) {
      Set<String> topics =
          adminClient.listTopics(new ListTopicsOptions().timeoutMs(10000)).names().get();
      debugF("Kafka topics: {}", topics);
    }
  }

  Properties createProducerConfig() throws InvalidConfigException {
    Properties props = new Properties();
    props.put(BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServer());
    props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    if (!isEmpty(kafkaConfig.getClientId())) {
      props.put(CLIENT_ID_CONFIG, kafkaConfig.getClientId());
    }
    props.put(RETRIES_CONFIG, kafkaConfig.getRetries());
    props.put(LINGER_MS_CONFIG, kafkaConfig.getLingerMs());
    props.put(BATCH_SIZE_CONFIG, kafkaConfig.getBatchSize());
    // reconnect back-off is 1 second
    props.put(RECONNECT_BACKOFF_MS_CONFIG, "1000");
    // maximum reconnect back-off is 10 seconds
    props.put(RECONNECT_BACKOFF_MAX_MS_CONFIG, "10000");
    configureAuth(props);
    return props;
  }

  Producer<String, String> createProducer() throws InvalidConfigException {
    debugF("kafkaConfig: {}", kafkaConfig);
    return new KafkaProducer<>(createProducerConfig());
  }

  Consumer<String, String> createConsumer() throws InvalidConfigException {
    return new KafkaConsumer<>(createConsumerConfig());
  }

  Properties createConsumerConfig() throws InvalidConfigException {
    Properties props = new Properties();

    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServer());
    if (!isEmpty(kafkaConfig.getClientId())) {
      props.put(ConsumerConfig.CLIENT_ID_CONFIG, kafkaConfig.getClientId());
    }
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "group-" + sessionName);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS);
    props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    configureAuth(props);

    return props;
  }

  void configureAuthSaslLogin(Properties props) throws InvalidConfigException {
    // Check if the username and password are set
    if (isEmpty(secret(SECRET_EVENTS_QUEUE_KAFKA_USERNAME))) {
      String msg =
          SECRET_EVENTS_QUEUE_KAFKA_USERNAME + " is not set. Please provide the Kafka username.";
      Log.error(msg);
      throw new InvalidConfigException(msg);
    }
    if (isEmpty(secret(SECRET_EVENTS_QUEUE_KAFKA_PASSWORD))) {
      String msg =
          SECRET_EVENTS_QUEUE_KAFKA_PASSWORD + " is not set. Please provide the Kafka password.";
      Log.error(msg);
      throw new InvalidConfigException(msg);
    }

    // Set the SASL login information
    props.put(
        "sasl.jaas.config",
        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
            + secret(SECRET_EVENTS_QUEUE_KAFKA_USERNAME)
            + "\" password=\""
            + secret(SECRET_EVENTS_QUEUE_KAFKA_PASSWORD)
            + "\";");
  }

  void configureAuth(Properties props) throws InvalidConfigException {
    switch (kafkaConfig.getSecurityProtocol()) {
      case SASL_PLAINTEXT:
        configureAuthSaslLogin(props);
        props.put(SECURITY_PROTOCOL_CONFIG, kafkaConfig.getSecurityProtocol().name());
        props.put(SASL_MECHANISM, kafkaConfig.getSaslMechanism().getValue());
        break;
      case SASL_SSL:
        configureAuthSaslLogin(props);
        props.put(SECURITY_PROTOCOL_CONFIG, kafkaConfig.getSecurityProtocol().name());
        props.put(SASL_MECHANISM, kafkaConfig.getSaslMechanism().getValue());

        if (!kafkaConfig.getSslVerifyCert()) {
          props.put(SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "");
          props.put(SSL_ENGINE_FACTORY_CLASS_CONFIG, TrustAllSslEngineFactory.class);
        } else {
          props.put(SSL_KEYSTORE_LOCATION_CONFIG, sslKeystoreLocation);
          props.put(SSL_TRUSTSTORE_LOCATION_CONFIG, sslTruststoreLocation);

          if (!isEmpty(secret(SECRET_SSL_KEYSTORE_PASSWORD)))
            props.put(SSL_KEYSTORE_PASSWORD_CONFIG, secret(SECRET_SSL_KEYSTORE_PASSWORD));
          if (!isEmpty(secret(SECRET_SSL_KEY_PASSWORD)))
            props.put(SSL_KEY_PASSWORD_CONFIG, secret(SECRET_SSL_KEY_PASSWORD));
          if (!isEmpty(secret(SECRET_SSL_TRUSTSTORE_PASSWORD)))
            props.put(SSL_TRUSTSTORE_PASSWORD_CONFIG, secret(SECRET_SSL_TRUSTSTORE_PASSWORD));
        }
        break;
      case PLAINTEXT:
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + kafkaConfig.getSecurityProtocol());
    }
  }

  String find(String sslKeystoreLocation) throws IOException {
    return findFileThenResource(sslKeystoreLocation).getAbsolutePath();
  }
}
