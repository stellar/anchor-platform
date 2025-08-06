package org.stellar.anchor.config;

import java.util.List;

@SuppressWarnings("SameReturnValue")
public interface StellarNetworkConfig {
  String getNetwork();

  String getStellarNetworkPassphrase();

  String getHorizonUrl();

  String getRpcUrl();

  RpcAuthConfig getRpcAuth();

  List<String> getLanguages();
}
