package org.stellar.anchor.config;

@SuppressWarnings("SameReturnValue")
public interface StellarNetworkConfig {
  ProviderType getType();

  String getNetwork();

  String getStellarNetworkPassphrase();

  String getHorizonUrl();

  String getRpcUrl();

  RpcAuthConfig getRpcAuth();

  enum ProviderType {
    HORIZON,
    RPC,
  }
}
