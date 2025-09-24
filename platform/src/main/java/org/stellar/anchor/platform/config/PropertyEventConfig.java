package org.stellar.anchor.platform.config;

import static org.springframework.validation.ValidationUtils.*;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.event.EventConfig;

@Data
public class PropertyEventConfig implements EventConfig, Validator {
  private boolean enabled = false;
  private PropertyQueueConfig queue;

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PropertyEventConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertyEventConfig config = (PropertyEventConfig) target;
    if (!config.isEnabled()) {
      return;
    }

    if (config.getQueue() != null) {
      queue.validate(config.getQueue(), errors);
    }
  }
}
