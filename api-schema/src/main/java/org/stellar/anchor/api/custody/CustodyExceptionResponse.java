package org.stellar.anchor.api.custody;

import lombok.Data;

@Data
public class CustodyExceptionResponse {
  String rawErrorMessage;

  public CustodyExceptionResponse(String rawErrorMessage) {
    this.rawErrorMessage = rawErrorMessage;
  }
}
