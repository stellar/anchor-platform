package org.stellar.anchor.client;

import com.google.gson.annotations.SerializedName;
import jakarta.annotation.Nonnull;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NonCustodialClient implements ClientConfig {
  /**
   * The unique name identifying the client. This should be a recognizable name that represents the
   * client entity.
   */
  @Nonnull String name;

  /** The domains associated with the client, used for verifying the client's identity. */
  Set<String> domains;

  @Override
  public boolean matchesDomain(String domain) {
    return domain != null && domains != null && domains.contains(domain);
  }

  /**
   * The URLs to which the service can send callbacks for different SEP types. Optional due to some
   * wallets may opt to poll instead, or may use polling first before implementing callbacks at a
   * later stage.
   */
  @SerializedName("callback_urls")
  CallbackUrls callbackUrls;
}
