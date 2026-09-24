package org.stellar.anchor.api.exception;

public class LedgerDecodeException extends LedgerException {
  public LedgerDecodeException(String message, Exception cause) {
    super(message, cause);
  }
}
