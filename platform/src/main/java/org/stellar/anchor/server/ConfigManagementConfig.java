package org.stellar.anchor.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.Sep10Config;
import org.stellar.anchor.config.Sep1Config;
import org.stellar.anchor.paymentservice.circle.config.CirclePaymentConfig;
import org.stellar.anchor.paymentservice.circle.config.StellarPaymentConfig;
import org.stellar.anchor.server.config.PropertyAppConfig;
import org.stellar.anchor.server.config.PropertySep10Config;
import org.stellar.anchor.server.config.PropertySep1Config;
import org.stellar.anchor.server.config.payment.PropertyCirclePaymentConfig;
import org.stellar.anchor.server.config.payment.PropertyStellarPaymentConfig;

@Configuration
public class ConfigManagementConfig {
  @Bean
  @ConfigurationProperties(prefix = "app")
  AppConfig appConfig() {
    return new PropertyAppConfig();
  }

  @Bean
  @ConfigurationProperties(prefix = "sep1")
  Sep1Config sep1Config() {
    return new PropertySep1Config();
  }

  @Bean
  @ConfigurationProperties(prefix = "sep10")
  Sep10Config sep10Config() {
    return new PropertySep10Config();
  }

  @Bean
  @ConfigurationProperties(prefix = "payment-gateway.circle")
  CirclePaymentConfig circlePaymentConfig() {
    return new PropertyCirclePaymentConfig();
  }

  @Bean
  @ConfigurationProperties(prefix = "payment-gateway.stellar")
  StellarPaymentConfig stellarPaymentConfig() {
    return new PropertyStellarPaymentConfig();
  }
}
