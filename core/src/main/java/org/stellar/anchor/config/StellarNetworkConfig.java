package org.stellar.anchor.config;

@SuppressWarnings("SameReturnValue")
public interface StellarNetworkConfig {
  String getNetwork();

  String getStellarNetworkPassphrase();

  String getHorizonUrl();

  String getRpcUrl();

  RpcAuthConfig getRpcAuth();

  //  List<String> getLanguages();
}
