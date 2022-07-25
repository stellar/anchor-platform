package org.stellar.anchor.platform.config;

import lombok.Data;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.Sep38Config;

@Data
public class PropertySep38Config implements Sep38Config, Validator {
  boolean enabled = false;
  String quoteIntegrationEndPoint;

  @Override
  public boolean supports(Class<?> clazz) {
    return Sep38Config.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    Sep38Config config = (Sep38Config) target;

    ValidationUtils.rejectIfEmpty(
        errors, "quoteIntegrationEndPoint", "empty-quoteIntegrationEndPoint");
  }
}
