package org.stellar.anchor.platform.config;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.Sep38Config;

@Data
public class PropertySep38Config implements Sep38Config, Validator {
  boolean enabled;

  @SerializedName("sep10_enforced")
  boolean sep10Enforced;

  @SerializedName("auth_enforced")
  boolean authEnforced;

  @Override
  public boolean supports(Class<?> clazz) {
    return Sep38Config.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(Object target, Errors errors) {
    PropertySep38Config config = (PropertySep38Config) target;

    if (config.isEnabled() && config.isAuthEnforced() != config.isSep10Enforced()) {
      errors.reject("sep38-auth-enforced-mismatch", "Mismatched auth_enforced and sep10_enforced");
    }
  }
}
