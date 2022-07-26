package org.stellar.anchor.platform.config;

import lombok.Data;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.Sep38Config;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.UrlConnectionStatus;
import org.stellar.anchor.util.UrlValidationUtil;

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

    UrlConnectionStatus urlStatus = UrlValidationUtil.validateUrl(config.getQuoteIntegrationEndPoint());
    if (urlStatus == UrlConnectionStatus.MALFORMED) {
      errors.rejectValue("quoteIntegrationEndPoint", "invalidUrl-quoteIntegrationEndpoint", "quoteIntegrationEndpoint is not in valid format");
    } else if (urlStatus == UrlConnectionStatus.UNREACHABLE) {
      Log.error("quoteIntegrationEndpoint field invalid: cannot connect to quote integration endpoint");
    }
  }
}
