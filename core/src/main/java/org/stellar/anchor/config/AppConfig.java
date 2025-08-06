package org.stellar.anchor.config;

import java.util.List;

@SuppressWarnings("SameReturnValue")
public interface AppConfig {
  String getNetwork();

  String getStellarNetworkPassphrase();

  String getHorizonUrl();

  String getRpcUrl();

  RpcAuthConfig getRpcAuth();

  List<String> getLanguages();
}
