package org.stellar.anchor.api.exception.custody;

import lombok.EqualsAndHashCode;
import org.stellar.anchor.api.exception.AnchorException;

@EqualsAndHashCode(callSuper = false)
public class CustodyTooManyRequestsException extends AnchorException {
  public CustodyTooManyRequestsException(String message) {
    super(message);
  }
}
