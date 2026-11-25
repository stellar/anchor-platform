package org.stellar.anchor.api.sep.sep10;

import lombok.Data;
import org.stellar.anchor.config.PII;

/**
 * The request body of the validation of the SEP-10 authentication flow.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0010.md">SEP-10</a>
 */
@Data
public class ValidationRequest {
  @PII String transaction;

  public static ValidationRequest of(String transaction) {
    ValidationRequest validationRequest = new ValidationRequest();
    validationRequest.transaction = transaction;
    return validationRequest;
  }
}
