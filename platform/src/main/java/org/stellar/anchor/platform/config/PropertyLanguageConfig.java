package org.stellar.anchor.platform.config;

import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.LanguageConfig;

@Getter
@Setter
public class PropertyLanguageConfig implements LanguageConfig, Validator {

  @Value("${languages}")
  private List<String> languages;

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PropertyLanguageConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertyLanguageConfig config = (PropertyLanguageConfig) target;

    if (config.getLanguages() != null) {
      for (String lang : config.getLanguages()) {
        try {
          //noinspection ResultOfMethodCallIgnored
          (new Locale.Builder()).setLanguageTag(lang).build();
        } catch (IllformedLocaleException ex) {
          errors.rejectValue(
              "languages",
              "languages-invalid",
              String.format("%s defined in languages is not valid", lang));
        }
      }
    }
  }
}
