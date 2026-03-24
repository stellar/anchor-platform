package org.stellar.anchor.api.exception.custody;

import lombok.EqualsAndHashCode;
import org.stellar.anchor.api.exception.AnchorException;

@EqualsAndHashCode(callSuper = false)
public class CustodyServiceUnavailableException extends AnchorException {
  public CustodyServiceUnavailableException(String message) {
    super(message);
  }
}
