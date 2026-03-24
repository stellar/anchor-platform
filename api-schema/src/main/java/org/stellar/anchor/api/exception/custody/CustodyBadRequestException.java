package org.stellar.anchor.api.exception.custody;

import lombok.EqualsAndHashCode;
import org.stellar.anchor.api.exception.AnchorException;

@EqualsAndHashCode(callSuper = false)
public class CustodyBadRequestException extends AnchorException {
  public CustodyBadRequestException(String message) {
    super(message);
  }
}
