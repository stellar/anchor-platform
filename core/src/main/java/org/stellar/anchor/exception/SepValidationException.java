package org.stellar.anchor.exception;

public class SepValidationException extends SepException {
  public SepValidationException(String message, Exception cause) {
    super(message, cause);
  }

  public SepValidationException(String message) {
    super(message);
  }
}
