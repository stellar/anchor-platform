package org.stellar.anchor.platform.config;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.StellarNetworkConfig;

@Data
public class MetricConfig implements Validator {
  private boolean enabled = false;
  private String prefix = "";

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return StellarNetworkConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    // NOOP
  }
}
