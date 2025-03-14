package org.stellar.anchor.config;

import java.util.List;

@SuppressWarnings("SameReturnValue")
public interface AppConfig {
  String getStellarNetwork();

  String getStellarNetworkPassphrase();

  String getHorizonUrl();

  String getRpcUrl();

  List<String> getLanguages();
}
