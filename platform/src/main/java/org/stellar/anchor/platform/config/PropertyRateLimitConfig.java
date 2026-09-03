package org.stellar.anchor.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.RateLimitConfig;

@Getter
@Setter
public class PropertyRateLimitConfig implements RateLimitConfig, Validator {
  boolean enabled = false;
  int maxTransactionsPerWindow = 10;
  long windowSeconds = 60;

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return RateLimitConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertyRateLimitConfig config = (PropertyRateLimitConfig) target;
    if (!config.enabled) {
      return;
    }

    if (config.maxTransactionsPerWindow <= 0) {
      errors.rejectValue(
          "maxTransactionsPerWindow",
          "rate-limit-max-transactions-per-window-invalid",
          "rate_limit.max_transactions_per_window must be greater than 0");
    }
    if (config.windowSeconds <= 0) {
      errors.rejectValue(
          "windowSeconds",
          "rate-limit-window-seconds-invalid",
          "rate_limit.window_seconds must be greater than 0");
    }
  }
}
