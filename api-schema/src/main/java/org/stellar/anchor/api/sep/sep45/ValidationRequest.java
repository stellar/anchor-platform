package org.stellar.anchor.api.sep.sep45;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * The request body of the validation of the SEP-45 authentication flow.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0045.md">SEP-45</a>
 */
@Builder
@Data
public class ValidationRequest {
  @JsonProperty("authorization_entries")
  private String authorizationEntries;
}
