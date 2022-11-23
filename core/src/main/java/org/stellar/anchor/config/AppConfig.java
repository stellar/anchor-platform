package org.stellar.anchor.config;

import java.util.List;

@SuppressWarnings("SameReturnValue")
public interface AppConfig {
  String getStellarNetworkPassphrase();

  String getHostUrl();

  String getHorizonUrl();

  List<String> getLanguages();
}
