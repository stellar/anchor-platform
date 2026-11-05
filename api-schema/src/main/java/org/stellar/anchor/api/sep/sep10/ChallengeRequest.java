package org.stellar.anchor.api.sep.sep10;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;

/**
 * The request body of the challenge of the SEP-10 authentication flow.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0010.md">SEP-10</a>
 */
@Builder
@Data
public class ChallengeRequest {
  String account;
  String memo;

  @JsonProperty("home_domain")
  @SerializedName("home_domain")
  String homeDomain;

  @JsonProperty("client_domain")
  @SerializedName("client_domain")
  String clientDomain;
}
