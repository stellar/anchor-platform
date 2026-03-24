package org.stellar.anchor.api.exception.custody;

import lombok.EqualsAndHashCode;
import org.stellar.anchor.api.exception.AnchorException;

@EqualsAndHashCode(callSuper = false)
public class CustodyNotFoundException extends AnchorException {
  public CustodyNotFoundException(String message) {
    super(message);
  }
}
