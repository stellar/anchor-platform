package org.stellar.anchor.platform.event;

import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.stellar.anchor.event.EventPublisher;
import org.stellar.anchor.event.models.AnchorEvent;
import org.stellar.anchor.platform.config.KafkaConfig;
import org.stellar.anchor.util.Log;

import static org.apache.kafka.clients.producer.ProducerConfig.*;

public class KafkaEventPublisher implements EventPublisher {
  Producer<String, AnchorEvent> producer;

  public KafkaEventPublisher(KafkaConfig kafkaConfig) {
    Log.debugF("kafkaConfig: {}", kafkaConfig);
    Properties props = new Properties();
    props.put(BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getBootstrapServers());
    props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    props.put(CLIENT_ID_CONFIG, kafkaConfig.getClientId());
    props.put(RETRIES_CONFIG, kafkaConfig.getRetries());
    props.put(LINGER_MS_CONFIG, kafkaConfig.getLingerMs());
    props.put(BATCH_SIZE_CONFIG, kafkaConfig.getBatchSize());

    createPublisher(props);
  }

  protected void createPublisher(Properties props) {
    this.producer = new KafkaProducer<>(props);
  }

  @Override
  public void publish(String queue, AnchorEvent event) {
    try {
      ProducerRecord<String, AnchorEvent> record = new ProducerRecord<>(queue, event);
      record.headers().add(new RecordHeader("type", event.getType().getBytes()));
      producer.send(record);
    } catch (Exception ex) {
      Log.errorEx(ex);
    }
  }
}
