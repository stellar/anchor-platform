package org.stellar.anchor.api.exception;

/**
 * Thrown when an identity exceeds the allowed number of transaction-creation requests within the
 * configured rate-limit window.
 */
public class SepRateLimitExceededException extends SepException {
  public SepRateLimitExceededException(String message) {
    super(message);
  }
}
