package org.stellar.anchor.api.exception;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
public class FireblocksException extends CustodyException {

  public FireblocksException(String rawMessage, int statusCode) {
    super(
        String.format(
            "Fireblocks API returned an error. HTTP status[%d], response[%s]",
            statusCode, rawMessage),
        rawMessage,
        statusCode);
  }

  public FireblocksException(Exception cause) {
    super("Exception occurred during request to Fireblocks API", cause.getMessage(), cause);
  }
}
