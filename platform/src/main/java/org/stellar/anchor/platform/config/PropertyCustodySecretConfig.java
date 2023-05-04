package org.stellar.anchor.platform.config;

import org.stellar.anchor.platform.configurator.SecretManager;

public class PropertyCustodySecretConfig implements CustodySecretConfig {

  public static final String SECRET_FIREBLOCKS_API_KEY = "secret.custody.fireblocks.api_key";
  public static final String SECRET_FIREBLOCKS_SECRET_KEY = "secret.custody.fireblocks.secret_key";
  public static final String SECRET_CUSTODY_API_AUTH_SECRET = "secret.custody_api.auth_secret";

  @Override
  public String getFireblocksApiKey() {
    return SecretManager.getInstance().get(SECRET_FIREBLOCKS_API_KEY);
  }

  @Override
  public String getFireblocksSecretKey() {
    return SecretManager.getInstance().get(SECRET_FIREBLOCKS_SECRET_KEY);
  }

  @Override
  public String getCustodyApiSecret() {
    return SecretManager.getInstance().get(SECRET_CUSTODY_API_AUTH_SECRET);
  }
}
