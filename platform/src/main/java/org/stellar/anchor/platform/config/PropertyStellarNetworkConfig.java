package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.Network.*;

import java.util.Objects;
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
    validateRpcAuth(config.getRpcAuth(), errors);
  }

  void validateRpcAuth(RpcAuthConfig rpcAuth, Errors errors) {
    if (Objects.requireNonNull(rpcAuth.getType()) == RpcAuthConfig.RpcAuthType.HEADER) {
      if (isEmpty(rpcAuth.getHeaderConfig().getName())) {
        errors.reject(
            "rpc-auth-header-name-empty",
            "The stellar_network.rpc_auth.header_config.header is not defined.");
      }
    }
  }

  void validateConfig(StellarNetworkConfig config, Errors errors) {
    ValidationUtils.rejectIfEmpty(
        errors, "network", "stellar-network-empty", "stellar_network.network is not defined.");

    config.getStellarNetworkPassphrase();

    switch (config.getType()) {
      case HORIZON:
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
        break;
      case RPC:
        if (isEmpty(config.getRpcUrl())) {
          errors.rejectValue(
              "rpcUrl", "rpc-url-empty", "The stellar_network.rpc_url is not defined.");
        } else {
          if (!NetUtil.isUrlValid(config.getRpcUrl())) {
            errors.rejectValue(
                "rpcUrl",
                "rpc-url-invalid",
                String.format(
                    "The stellar_network.rpc_url:%s is not in valid format.", config.getRpcUrl()));
          }
        }
        break;
      default:
        errors.rejectValue(
            "type",
            "stellar-network-type-invalid",
            String.format("The stellar_network.type:%s is not valid.", config.getType()));
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
