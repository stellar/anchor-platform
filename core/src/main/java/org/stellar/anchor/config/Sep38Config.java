package org.stellar.anchor.config;

@SuppressWarnings("SameReturnValue")
public interface Sep38Config {
  boolean isEnabled();

  @Deprecated
  boolean isSep10Enforced();

  boolean isAuthEnforced();
}
