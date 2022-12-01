package org.stellar.anchor.platform.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;

@Data
public class PaymentObserverConfig implements Validator {
  PaymentObserverType type;
  StellarPaymentObserverConfig stellar;

  public enum PaymentObserverType {
    STELLAR
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class StellarPaymentObserverConfig {
    int silenceCheckInterval;
    int silenceTimeout;
    int silenceTimeoutRetries;
    int initialStreamBackoffTime;
    int maxStreamBackoffTime;
    int initialEventBackoffTime;
    int maxEventBackoffTime;
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PlatformApiConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PaymentObserverConfig config = (PaymentObserverConfig) target;
    validateStellar(config, errors);
    validateConfig(config, errors);
  }

  void validateConfig(PaymentObserverConfig config, Errors errors) {
    ValidationUtils.rejectIfEmptyOrWhitespace(
        errors,
        "payment_observer.type",
        "empty-payment-observer-type",
        "payment_observer.type must not be empty");
  }

  void validateStellar(PaymentObserverConfig config, Errors errors) {
    if (PaymentObserverType.STELLAR.equals(config.type)) {
      if (config.stellar == null) {
        errors.reject(
            "empty-payment-observer-stellar",
            "The payment_observer.type is set ast STELLAR. Please populate the payment_observer.stellar fields.");
      } else {
        if (config.stellar.silenceCheckInterval < 1) {
          errors.reject(
              "invalid-payment-observer-silence-check-interval",
              "The payment_observer.stellar.silence_check_interval must be greater than 1");
        }
        if (config.stellar.silenceTimeout < 5) {
          errors.reject(
              "invalid-payment-observer-stellar-silence-timeout",
              "The payment_observer.stellar.silence_timeout must be greater than 5");
        }
        if (config.stellar.silenceTimeoutRetries < 1) {
          errors.reject(
              "invalid-payment-observer-stellar-silence-timeout-retries",
              "The payment_observer.stellar.silence_timeout_retries must be equal or greater than 1");
        }
        if (config.stellar.initialStreamBackoffTime <= 1) {
          errors.reject(
              "invalid-payment-observer-stellar-initial-stream-backoff-time",
              "The payment_observer.stellar.initial_stream_backoff must be equal or greater than 1");
        }
        if (config.stellar.maxStreamBackoffTime <= 2) {
          errors.reject(
              "invalid-payment-observer-stellar-max-stream-backoff-time",
              "The payment_observer.stellar.max_stream_backoff must be equal or greater than 2");
        }
        if (config.stellar.initialEventBackoffTime <= 1) {
          errors.reject(
              "invalid-payment-observer-stellar-initial-event-backoff-time",
              "The payment_observer.stellar.initial_event_backoff_time must be equal or greater than 1");
        }
        if (config.stellar.maxEventBackoffTime <= 2) {
          errors.reject(
              "invalid-payment-observer-stellar-max-event-backoff-time",
              "The payment_observer.stellar.max_event_backoff_time must be equal or greater than 2");
        }
      }
    }
  }
}
