package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.Network.*;

import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.util.NetUtil;

@Data
public class PropertyAppConfig implements AppConfig, Validator {
  @Value("${stellar_network.network}")
  private String stellarNetwork;

  @Value("${stellar_network.horizon_url}")
  private String horizonUrl;

  @Value("${stellar_network.rpc_url}")
  private String rpcUrl;

  @Value("${languages}")
  private List<String> languages;

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return AppConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    AppConfig config = (AppConfig) target;

    validateConfig(config, errors);
    validateLanguage(config, errors);
  }

  void validateConfig(AppConfig config, Errors errors) {
    ValidationUtils.rejectIfEmpty(
        errors,
        "stellarNetwork",
        "stellar-network-empty",
        "stellar_network.network is not defined.");

    try {
      config.getStellarNetworkPassphrase();
    } catch (Exception ex) {
      errors.rejectValue(
          "stellarNetwork",
          "stellar-network-invalid",
          String.format(
              "The stellar_network.network:%s is not valid. Please check the configuration.",
              config.getStellarNetwork()));
    }

    if (isEmpty(config.getHorizonUrl())) {
      errors.rejectValue(
          "horizonUrl", "horizon-url-empty", "The stellar_network.horizon_url is not defined.");
    } else {
      if (!NetUtil.isUrlValid(config.getHorizonUrl())) {
        errors.rejectValue(
            "horizonUrl",
            "horizon-url-invalid",
            String.format(
                "The stellar_network.horizon_url:%s is not in valid format.",
                config.getHorizonUrl()));
      }
    }

    if (isEmpty(config.getRpcUrl())) {
      // TODO: this can only be empty when SEP-45 is disabled
    } else {
      if (!NetUtil.isUrlValid(config.getRpcUrl())) {
        errors.rejectValue(
            "rpcUrl",
            "rpc-url-invalid",
            String.format(
                "The stellar_network.rpc_url:%s is not in valid format.", config.getRpcUrl()));
      }
    }
  }

  void validateLanguage(AppConfig config, Errors errors) {
    if (config.getLanguages() != null) {
      for (String lang : config.getLanguages()) {
        try {
          new Locale.Builder().setLanguageTag(lang);
        } catch (IllformedLocaleException ex) {
          errors.rejectValue(
              "languages",
              "languages-invalid",
              String.format("%s defined in languages is not valid", lang));
        }
      }
    }
  }

  @Override
  public String getStellarNetworkPassphrase() {
    switch (stellarNetwork.toUpperCase()) {
      case "TESTNET":
        return TESTNET.getNetworkPassphrase();
      case "PUBLIC":
        return PUBLIC.getNetworkPassphrase();
      case "FUTURENET":
        return FUTURENET.getNetworkPassphrase();
      default:
        throw new RuntimeException(
            "Invalid stellar network " + stellarNetwork + ". Please check the configuration.");
    }
  }
}
