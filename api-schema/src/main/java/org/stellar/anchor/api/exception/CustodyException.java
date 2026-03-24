package org.stellar.anchor.api.exception;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.hc.core5.http.HttpStatus;

@EqualsAndHashCode(callSuper = false)
public class CustodyException extends AnchorException {

  @Getter private int statusCode;
  @Getter private String rawMessage;

  public CustodyException(String message) {
    super(message);
  }

  public CustodyException(String rawMessage, int statusCode) {
    this(
        String.format(
            "Custody API returned an error. HTTP status[%d], response[%s]", statusCode, rawMessage),
        rawMessage,
        statusCode);
  }

  public CustodyException(String message, String rawMessage, int statusCode) {
    super(message);
    this.statusCode = statusCode;
    this.rawMessage = rawMessage;
  }

  public CustodyException(Exception cause) {
    super("Exception occurred during request to Custody API", cause);
    this.rawMessage = cause.getMessage();
    this.statusCode = HttpStatus.SC_SERVICE_UNAVAILABLE;
  }

  public CustodyException(String message, String rawMessage, Exception cause) {
    super(message, cause);
    this.rawMessage = rawMessage;
    this.statusCode = HttpStatus.SC_SERVICE_UNAVAILABLE;
  }
}
