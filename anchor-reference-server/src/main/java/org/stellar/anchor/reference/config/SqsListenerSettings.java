package org.stellar.anchor.reference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sqs.listener")
public class SqsListenerSettings {
  String region;
  Queues queues;
  String accessKey;
  String secretKey;

  @Data
  public static class Queues {
    String all;
    String quoteCreated;
    String transactionCreated;
    String transactionPaymentReceived;
    String transactionError;
  }
}
