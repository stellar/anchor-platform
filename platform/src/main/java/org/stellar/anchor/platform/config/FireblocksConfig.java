package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.util.NetUtil;

@Data
public class FireblocksConfig implements Validator {

  private Boolean enabled;
  private String baseUrl;
  private String vaultAccountId;
  private SecretConfig secretConfig;
  private String transactionsReconciliationCron;

  public FireblocksConfig(SecretConfig secretConfig) {
    this.secretConfig = secretConfig;
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return FireblocksConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    if (enabled) {
      validateBaseUrl(errors);
      validateApiKey(errors);
      validateSecretKey(errors);
      validateTransactionsReconciliationCron(errors);
    }
  }

  private void validateBaseUrl(Errors errors) {
    if (isEmpty(baseUrl)) {
      errors.rejectValue(
          "baseUrl",
          "custody-fireblocks-base-url-empty",
          "The custody.fireblocks.base_url cannot be empty and must be defined");
    }
    if (!NetUtil.isUrlValid(baseUrl)) {
      errors.rejectValue(
          "baseUrl",
          "custody-fireblocks-base-url-invalid",
          "The custody.fireblocks.base_url is not a valid URL");
    }
  }

  private void validateApiKey(Errors errors) {
    if (isEmpty(secretConfig.getFireblocksApiKey())) {
      errors.reject(
          "secret-custody-fireblocks-api-key-empty",
          "Please set environment variable secret.custody.fireblocks.api_key or SECRET_CUSTODY_FIREBLOCKS_API_KEY");
    }
  }

  private void validateSecretKey(Errors errors) {
    if (isEmpty(secretConfig.getFireblocksSecretKey())) {
      errors.reject(
          "secret-custody-fireblocks-secret-key-empty",
          "Please set environment variable secret.custody.fireblocks.secret_key or SECRET_CUSTODY_FIREBLOCKS_SECRET_KEY");
    }
  }

  private void validateTransactionsReconciliationCron(Errors errors) {
    if (isEmpty(transactionsReconciliationCron)) {
      errors.reject(
          "custody-fireblocks-transactions-reconciliation-cron-empty",
          "The custody.fireblocks.transactions_reconciliation_cron is empty");
    }
    if (!CronExpression.isValidExpression(transactionsReconciliationCron)) {
      errors.reject(
          "custody-fireblocks-transactions-reconciliation-cron-invalid",
          "The custody.fireblocks.transactions_reconciliation_cron is invalid");
    }
  }
}
