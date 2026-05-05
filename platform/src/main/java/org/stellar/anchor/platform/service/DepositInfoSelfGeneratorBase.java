package org.stellar.anchor.platform.service;

import java.security.SecureRandom;

public class DepositInfoSelfGeneratorBase {
  private static final SecureRandom RANDOM = new SecureRandom();

  protected String generateMemoId() {
    return String.valueOf(RANDOM.nextLong(1, Long.MAX_VALUE));
  }
}
