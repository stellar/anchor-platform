package org.stellar.anchor.platform.config;

import static org.stellar.anchor.config.Sep31Config.DepositInfoGeneratorType.SELF;
import static org.stellar.anchor.config.Sep31Config.PaymentType.STRICT_SEND;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.Sep31Config;

@Data
public class PropertySep31Config implements Sep31Config, Validator {
  boolean enabled;
  PaymentType paymentType = STRICT_SEND;
  DepositInfoGeneratorType depositInfoGeneratorType;

  AssetService assetService;

  public PropertySep31Config(AssetService assetService) {
    this.assetService = assetService;
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return Sep31Config.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    if (enabled) {
      validateDepositInfoGeneratorType(errors);
    }
  }

  void validateDepositInfoGeneratorType(Errors errors) {
    if (SELF == depositInfoGeneratorType) {
      for (StellarAssetInfo asset : assetService.getStellarAssets()) {
        if (!asset.getCode().equals("native") && isEmpty(asset.getDistributionAccount())) {
          errors.rejectValue(
              "depositInfoGeneratorType",
              "sep31-deposit-info-generator-type",
              "[self] deposit info generator type is not supported when distribution account is not set");
        }
      }
    }
  }
}
