package org.stellar.anchor.api.exception;

/** Thrown when a SEP-10 GET /auth request is not authenticated. */
public class SepMissingAuthHeaderException extends SepException {
  public SepMissingAuthHeaderException(String message, Exception cause) {
    super(message, cause);
  }

  public SepMissingAuthHeaderException(String message) {
    super(message);
  }
}
