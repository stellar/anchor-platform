package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.Network.*;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.RpcAuthConfig;
import org.stellar.anchor.config.StellarNetworkConfig;
import org.stellar.anchor.util.NetUtil;

@Data
public class PropertyStellarNetworkConfig implements StellarNetworkConfig, Validator {
  private ProviderType type;
  private String network;
  private String horizonUrl;
  private String rpcUrl;
  private RpcAuthConfig rpcAuth;

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return StellarNetworkConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    StellarNetworkConfig config = (StellarNetworkConfig) target;

    validateConfig(config, errors);
  }

  void validateConfig(StellarNetworkConfig config, Errors errors) {
    ValidationUtils.rejectIfEmpty(
        errors, "network", "stellar-network-empty", "stellar_network.network is not defined.");

    try {
      config.getStellarNetworkPassphrase();
    } catch (Exception ex) {
      errors.rejectValue(
          "network",
          "stellar-network-invalid",
          String.format(
              "The stellar_network.network:%s is not valid. Please check the configuration.",
              config.getNetwork()));
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

    if (!isEmpty(config.getRpcUrl())) {
      if (!NetUtil.isUrlValid(config.getRpcUrl())) {
        errors.rejectValue(
            "rpcUrl",
            "rpc-url-invalid",
            String.format(
                "The stellar_network.rpc_url:%s is not in valid format.", config.getRpcUrl()));
      }
    }
  }

  @Override
  public String getStellarNetworkPassphrase() {
    return switch (network.toUpperCase()) {
      case "TESTNET" -> TESTNET.getNetworkPassphrase();
      case "PUBLIC" -> PUBLIC.getNetworkPassphrase();
      case "FUTURENET" -> FUTURENET.getNetworkPassphrase();
      default ->
          throw new RuntimeException(
              "Invalid stellar network " + network + ". Please check the configuration.");
    };
  }
}
