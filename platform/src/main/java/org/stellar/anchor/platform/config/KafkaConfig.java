package org.stellar.anchor.platform.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KafkaConfig {
  /**
   * A comma-separated list of host:port pairs that are the addresses of one or more brokers in a
   * Kafka cluster, e.g. localhost:9092 or localhost:9092,another.host:9092.
   */
  String bootstrapServer;

  /** The client ID. If left empty, it is randomly generated. */
  String clientId;

  /**
   * Determines how many times the producer will attempt to send a message before marking it as
   * failed.
   */
  int retries;

  /** Determines the time to wait before sending messages out to Kafka. */
  int lingerMs;

  /** Determines the maximum amount of data to be collected before sending the batch. */
  int batchSize;

  /** Determines the maximum amount of time to wait for the batch to be filled. */
  int pollTimeoutSeconds;

  /** The security protocol used to communicate with brokers. */
  SecurityProtocol securityProtocol;

  /** The SASL mechanism used for authentication. */
  SaslMechanism saslMechanism;

  /** The certificate verification flag. */
  Boolean sslVerifyCert = Boolean.TRUE;

  /** the SSL keystore location. */
  String sslKeystoreLocation;

  /** the SSL truststore location. */
  String sslTruststoreLocation;

  public enum SecurityProtocol {
    PLAINTEXT,
    SASL_PLAINTEXT,
    SASL_SSL
  }

  public enum SaslMechanism {
    PLAIN("PLAIN");

    String value;

    SaslMechanism(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }
}
