package org.stellar.anchor.api.exception;

public class LedgerException extends AnchorException {
  public LedgerException(String message, Exception cause) {
    super(message, cause);
  }

  public LedgerException(String message) {
    super(message);
  }
}
