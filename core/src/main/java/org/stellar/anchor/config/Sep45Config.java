package org.stellar.anchor.config;

import java.util.List;

public interface Sep45Config {

  Boolean getEnabled();

  String getWebAuthDomain();

  String getWebAuthContractId();

  List<String> getHomeDomains();

  Integer getJwtTimeout();
}
