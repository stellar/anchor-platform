package org.stellar.anchor.platform.config;

import static org.stellar.anchor.config.Sep6Config.DepositInfoGeneratorType.CUSTODY;
import static org.stellar.anchor.config.Sep6Config.DepositInfoGeneratorType.SELF;
import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.anchor.util.StringHelper.snakeToCamelCase;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.*;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep6Config;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.util.KeyUtil;
import org.stellar.anchor.util.NetUtil;

@Data
public class PropertySep6Config implements Sep6Config, Validator {
  static List<String> validFields =
      Arrays.stream(JdbcSep6Transaction.class.getDeclaredFields())
          .sequential()
          .map(Field::getName)
          .collect(Collectors.toList());
  boolean enabled;
  Features features;
  DepositInfoGeneratorType depositInfoGeneratorType;
  Long initialUserDeadlineSeconds;
  CustodyConfig custodyConfig;
  AssetService assetService;
  MoreInfoUrlConfig moreInfoUrl;
  SecretConfig secretConfig;

  public PropertySep6Config(
      CustodyConfig custodyConfig, AssetService assetService, SecretConfig secretConfig) {
    this.custodyConfig = custodyConfig;
    this.assetService = assetService;
    this.secretConfig = secretConfig;
  }

  @PostConstruct
  public void postConstruct() {
    if (initialUserDeadlineSeconds != null && initialUserDeadlineSeconds <= 0) {
      initialUserDeadlineSeconds = null;
    }
  }

  @Override
  public boolean supports(@NonNull Class<?> clazz) {
    return Sep6Config.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NonNull Object target, @NonNull Errors errors) {
    if (enabled) {
      if (features == null) {
        errors.rejectValue("features", "sep6-features-invalid", "sep6.features is not defined");
      } else if (features.isAccountCreation()) {
        errors.rejectValue(
            "features",
            "sep6-features-account-creation-invalid",
            "sep6.features.account_creation: account creation is not supported");
      } else if (features.isClaimableBalances()) {
        errors.rejectValue(
            "features",
            "sep6-features-claimable-balances-invalid",
            "sep6.features.claimable_balances: claimable balances are not supported");
      }
      validateDepositInfoGeneratorType(errors);
      validateMoreInfoUrlConfig(errors);
    }
  }

  void validateMoreInfoUrlConfig(Errors errors) {
    if (moreInfoUrl != null) {
      if (!NetUtil.isUrlValid(moreInfoUrl.baseUrl)) {
        errors.rejectValue(
            "moreInfoUrl",
            "sep6-more-info-url-base-url-not-valid",
            String.format(
                "sep6.more_info_url.base_url:[%s] is not a valid URL.", moreInfoUrl.baseUrl));
      }
      if (moreInfoUrl.jwtExpiration <= 0) {
        errors.rejectValue(
            "moreInfoUrl",
            "sep6-more-info-url-jwt-expiration-not-valid",
            String.format(
                "sep6.more_info_url.jwt_expiration:[%s] must be greater than 0.",
                moreInfoUrl.jwtExpiration));
      }
      for (String field : moreInfoUrl.txnFields) {
        if (!isEmpty(field)) {
          if (!validFields.contains(snakeToCamelCase(field))) {
            errors.rejectValue(
                "moreInfoUrl.txnFields",
                "sep6-more_info-url-txn-fields-not-valid",
                String.format(
                    "sep6.more_info_url.txn_fields contains the field:[%s] which is not valid transaction field",
                    field));
          }
        }
      }
      if (isEmpty(secretConfig.getSep6MoreInfoUrlJwtSecret())) {
        errors.reject(
            "sep6-more-info-url-jwt-secret-not-defined",
            "Please set the secret.sep6.more_info_url.jwt_secret or SECRET_SEP6_MORE_INFO_URL_JWT_SECRET environment variable");
      }
      KeyUtil.rejectWeakJWTSecret(
          secretConfig.getSep6MoreInfoUrlJwtSecret(),
          errors,
          "secret.sep6.more_info_url.jwt_secret");
    }
  }

  void validateDepositInfoGeneratorType(Errors errors) {
    if (custodyConfig.isCustodyIntegrationEnabled() && CUSTODY != depositInfoGeneratorType) {
      errors.rejectValue(
          "depositInfoGeneratorType",
          "sep6-deposit-info-generator-type",
          String.format(
              "[%s] deposit info generator type is not supported when custody integration is enabled",
              depositInfoGeneratorType.toString().toLowerCase()));
    } else if (!custodyConfig.isCustodyIntegrationEnabled()
        && CUSTODY == depositInfoGeneratorType) {
      errors.rejectValue(
          "depositInfoGeneratorType",
          "sep6-deposit-info-generator-type",
          "[custody] deposit info generator type is not supported when custody integration is disabled");
    }

    if (SELF == depositInfoGeneratorType) {
      for (AssetInfo asset : assetService.listStellarAssets()) {
        if (!asset.getCode().equals("native") && isEmpty(asset.getDistributionAccount())) {
          errors.rejectValue(
              "depositInfoGeneratorType",
              "sep6-deposit-info-generator-type",
              "[self] deposit info generator type is not supported when distribution account is not set");
        }
      }
    }
  }
}
