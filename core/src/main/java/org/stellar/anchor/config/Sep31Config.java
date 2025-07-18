package org.stellar.anchor.config;

public interface Sep31Config {
  boolean isEnabled();

  PaymentType getPaymentType();

  DepositInfoGeneratorType getDepositInfoGeneratorType();

  enum PaymentType {
    STRICT_SEND,
    STRICT_RECEIVE
  }

  enum DepositInfoGeneratorType {
    SELF,
    NONE
  }
}
