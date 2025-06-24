package org.stellar.anchor.api.sep.sep45;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * The response body of the challenge of the SEP-45 authentication flow.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0045.md">SEP-45</a>
 */
@Builder
@Data
public class ChallengeResponse {
  @JsonProperty("authorization_entries")
  private String authorizationEntries;

  @JsonProperty("network_passphrase")
  private String networkPassphrase;
}
