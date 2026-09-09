package org.stellar.anchor.api.platform;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * The response body of the GET /transactions/{id} endpoint of the Platform API.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Platform%20API.yml">Platform
 *     API</a>
 */
@Getter
@SuperBuilder
@NoArgsConstructor
public class GetTransactionResponse extends PlatformTransactionData {
  String fundingMethod;

  /**
   * The per-transaction field values submitted so far (e.g. via SEP-31 POST/PATCH transactions).
   * Currently only populated for SEP-31 -- SEP-6/24 have no equivalent per-transaction field store.
   * Consumers needing to know which of these correspond to still-outstanding corrections should
   * cross-reference {@link PlatformTransactionData#getRequiredInfoUpdates()}.
   */
  Map<String, String> fields;
}
