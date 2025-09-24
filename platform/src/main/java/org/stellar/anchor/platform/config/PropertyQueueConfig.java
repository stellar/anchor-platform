package org.stellar.anchor.platform.config;

import static java.lang.String.*;
import static org.springframework.validation.ValidationUtils.rejectIfEmptyOrWhitespace;
import static org.stellar.anchor.platform.utils.ResourceHelper.*;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.io.IOException;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.event.*;

@Data
public class PropertyQueueConfig implements QueueConfig, Validator {
  QueueType type;
  KafkaConfig kafka;
  SqsConfig sqs;
  MskConfig msk;

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PropertyQueueConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertyQueueConfig config = (PropertyQueueConfig) target;
    if (config.getType() == null) {
      errors.reject(
          "queue-type-empty",
          "queue.type is not defined. Please specify the type: KAFKA, SQS, or MSK");
      return;
    }

    switch (config.getType()) {
      case KAFKA:
        validateKafka(config, errors);
        break;
      case SQS:
        validateSqs(config, errors);
        break;
      case MSK:
        validateMsk(config, errors);
        break;
      default:
        errors.reject("invalidType-queue-type", "queue.type must be one of 'KAFKA', 'SQS', or MSK");
    }
  }

  void validateKafka(PropertyQueueConfig config, Errors errors) {
    if (config.getKafka() == null) {
      errors.reject(
          "queue-kafka-empty",
          "queue.kafka is not defined. Please specify the Kafka configuration.");
    } else {
      KafkaConfig kafkaConfig = config.getKafka();
      if (isEmpty(kafkaConfig.getBootstrapServer())) {
        errors.reject(
            "kafka-bootstrap-server-empty",
            "queue.kafka.bootstrap_server is not defined. Please specify the Kafka bootstrap server.");
      }
      if (kafkaConfig.getRetries() < 0) {
        errors.reject(
            "kafka-retries-invalid", "queue.kafka.retries must be greater than or equal to 0.");
      }

      if (kafkaConfig.getLingerMs() < 0) {
        errors.reject(
            "kafka-linger-ms-invalid", "queue.kafka.linger_ms must be greater than or equal to 0.");
      }

      if (kafkaConfig.getBatchSize() <= 0) {
        errors.reject("kafka-batch-size-invalid", "queue.kafka.batch_size must be greater than 0.");
      }

      if (kafkaConfig.getPollTimeoutSeconds() <= 0) {
        errors.reject(
            "kafka-poll-timeout-seconds-invalid",
            "queue.kafka.poll_timeout_seconds must be greater than 0.");
      }

      if (kafkaConfig.getSecurityProtocol() == null) {
        errors.reject(
            "kafka-security-protocol-empty", "queue.kafka.security_protocol must be defined.");
      }

      if (kafkaConfig.getSecurityProtocol() == KafkaConfig.SecurityProtocol.SASL_PLAINTEXT) {
        if (kafkaConfig.getSaslMechanism() == null) {
          errors.reject(
              "kafka-sasl-mechanism-empty",
              "events.queue.kafka.sasl_mechanism must be defined when queue.kafka.security_protocol is SASL_PLAINTEXT.");
        }
      }

      if (kafkaConfig.getSecurityProtocol() == KafkaConfig.SecurityProtocol.SASL_SSL
          && kafkaConfig.getSslVerifyCert()) {
        if (kafkaConfig.getSaslMechanism() == null) {
          errors.reject(
              "kafka-sasl-mechanism-empty",
              "events.queue.kafka.sasl_mechanism must be defined when queue.kafka.security_protocol is SASL_SSL.");
        }
        if (isEmpty(kafkaConfig.getSslKeystoreLocation())) {
          errors.reject(
              "kafka-ssl-keystore-location-empty",
              "queue.kafka.ssl_keystore_location must be defined if queue.kafka.security_protocol is SASL_SSL.");
        } else {
          try {
            findFileThenResource(kafkaConfig.getSslKeystoreLocation());
          } catch (IOException e) {
            errors.reject(
                "kafka-ssl-keystore-location-not-found",
                format(
                    "queue.kafka.ssl_keystore_location file \"%s\" not found.",
                    kafkaConfig.getSslKeystoreLocation()));
          }
        }
        if (isEmpty(kafkaConfig.getSslTruststoreLocation())) {
          errors.reject(
              "kafka-ssl-truststore-location-empty",
              "queue.kafka.ssl_truststore_location must be defined if securityProtocol is SASL_SSL.");
        } else {
          try {
            findFileThenResource(kafkaConfig.getSslTruststoreLocation());
          } catch (IOException e) {
            errors.reject(
                "kafka-ssl-truststore-location-not-found",
                format(
                    "queue.kafka.ssl_truststore_location file \"%s\" not found.",
                    kafkaConfig.getSslTruststoreLocation()));
          }
        }
      }
    }
  }

  void validateMsk(PropertyQueueConfig config, Errors errors) {
    rejectIfEmptyOrWhitespace(errors, "msk.bootstrapServer", "msk-bootstrap-server-empty");

    if (config.msk.retries < 0) {
      errors.reject("msk-retries-invalid", "events.publisher.msk.retries must be greater than 0");
    }

    if (config.msk.lingerMs < 0) {
      errors.reject(
          "msk-linger-ms-invalid", "events.publisher.msk.linger_ms must be greater than 0");
    }

    if (config.msk.batchSize < 0) {
      errors.reject(
          "msk-batch-size-invalid", "events.publisher.msk.batch_size must be greater than 0");
    }
  }

  void validateSqs(PropertyQueueConfig config, Errors errors) {
    if (isEmpty(config.getSqs().awsRegion)) {
      errors.reject("sqs-aws-region-empty", "events.publisher.sqs.aws_region must be defined");
    }
  }
}
