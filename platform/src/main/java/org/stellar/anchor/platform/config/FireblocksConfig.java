package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.RSAUtil.RSA_ALGORITHM;
import static org.stellar.anchor.util.RSAUtil.generatePrivateKey;
import static org.stellar.anchor.util.RSAUtil.generatePublicKey;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.config.CustodySecretConfig;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.NetUtil;
import org.stellar.anchor.util.RSAUtil;

@Data
public class FireblocksConfig implements Validator {

  private String baseUrl;
  private String vaultAccountId;
  private CustodySecretConfig secretConfig;
  private String publicKey;
  private RetryConfig retryConfig;
  private Reconciliation reconciliation;
  private Map<String, String> assetMappings;

  public FireblocksConfig(CustodySecretConfig secretConfig) {
    this.secretConfig = secretConfig;
  }

  public void setAssetMappings(String assetMappings) {
    if (StringUtils.isEmpty(assetMappings)) {
      this.assetMappings = Map.of();
    } else {
      this.assetMappings =
          Arrays.stream(assetMappings.split(StringUtils.LF))
              .collect(
                  Collectors.toMap(
                      mapping -> mapping.substring(mapping.indexOf(StringUtils.SPACE) + 1),
                      mapping -> mapping.substring(0, mapping.indexOf(StringUtils.SPACE))));
    }
  }

  /**
   * Get Fireblocks asset code by Stellar asset code
   *
   * @return Fireblocks asset code or null if no mapping found
   */
  public String getFireblocksAssetCode(String stellarAssetCode) throws InvalidConfigException {
    if (assetMappings.containsKey(stellarAssetCode)) {
      return assetMappings.get(stellarAssetCode);
    }

    String message =
        String.format(
            "Unable to find Fireblocks asset code by Stellar asset code [%s]", stellarAssetCode);
    Log.warnF(
        message + " Please add corresponding asset mapping in custody.fireblocks.asset_mapping");
    throw new InvalidConfigException(message);
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return FireblocksConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    validateBaseUrl(errors);
    validateVaultAccountId(errors);
    validateApiKey(errors);
    validateSecretKey(errors);
    validateReconciliationCronExpression(errors);
    validateReconciliationMaxAttempts(errors);
    validatePublicKey(errors);
    validateRetryMaxAttempts(errors);
    validateRetryDelay(errors);
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

  private void validateVaultAccountId(Errors errors) {
    if (isEmpty(vaultAccountId)) {
      errors.rejectValue(
          "vaultAccountId",
          "custody-fireblocks-vault-account-id-empty",
          "The custody.fireblocks.vault_account_id cannot be empty and must be defined");
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
    if (!RSAUtil.isValidPrivateKey(secretConfig.getFireblocksSecretKey(), RSAUtil.RSA_ALGORITHM)) {
      errors.reject(
          "secret-custody-fireblocks-secret_key-invalid",
          "The secret-custody-fireblocks-secret_key is invalid");
    }
  }

  private void validateReconciliationCronExpression(Errors errors) {
    if (isEmpty(reconciliation.cronExpression)) {
      errors.reject(
          "custody-fireblocks-reconciliation-cron_expression-empty",
          "The custody.fireblocks.reconciliation.cron_expression is empty");
    }
    if (!CronExpression.isValidExpression(reconciliation.cronExpression)) {
      errors.reject(
          "custody-fireblocks-reconciliation-cron_expression-invalid",
          "The custody.fireblocks.reconciliation.cron_expression is invalid");
    }
  }

  private void validateReconciliationMaxAttempts(Errors errors) {
    if (reconciliation.maxAttempts < 0) {
      errors.reject(
          "custody-fireblocks-reconciliation-max_attempts-invalid",
          "custody.fireblocks.reconciliation.max_attempts must be greater than or equal to 0");
    }
  }

  public void validatePublicKey(Errors errors) {
    if (isEmpty(publicKey)) {
      errors.reject(
          "custody-fireblocks-public_key-empty", "The custody.fireblocks.public_key is empty");
    }
    if (!RSAUtil.isValidPublicKey(publicKey, RSAUtil.RSA_ALGORITHM)) {
      errors.reject(
          "custody-fireblocks-public_key-invalid", "The custody-fireblocks-public_key is invalid");
    }
  }

  public void validateRetryMaxAttempts(Errors errors) {
    if (retryConfig.maxAttempts < 0) {
      errors.reject(
          "custody-fireblocks-retry_config-max_attempts-invalid",
          "custody.fireblocks.retry_config.max_attempts must be greater than or equal to 0");
    }
  }

  public void validateRetryDelay(Errors errors) {
    if (retryConfig.delay < 0) {
      errors.reject(
          "custody-fireblocks-retry_config-delay-invalid",
          "custody.fireblocks.retry_config.delay must be greater than or equal to 0");
    }
  }

  /**
   * Get Fireblocks public key
   *
   * @return public key
   */
  public PublicKey getFireblocksPublicKey() throws InvalidConfigException {
    try {
      return generatePublicKey(publicKey, RSA_ALGORITHM);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new InvalidConfigException(List.of("Failed to generate Fireblocks public key"), e);
    }
  }

  /**
   * Get Fireblocks private key
   *
   * @return private key
   */
  public PrivateKey getFireblocksPrivateKey() throws InvalidConfigException {
    try {
      return generatePrivateKey(secretConfig.getFireblocksSecretKey(), RSA_ALGORITHM);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new InvalidConfigException(List.of("Failed to generate Fireblocks private key"), e);
    }
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class RetryConfig {

    private int maxAttempts;
    private int delay;
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class Reconciliation {
    private int maxAttempts;
    private String cronExpression;
  }
}
