package org.stellar.anchor.api.platform;

import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.asset.AssetInfo;

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

  /**
   * The real per-field metadata (description/choices/optional) backing {@link
   * PlatformTransactionData#getRequiredInfoUpdates()}, keyed by field name -- populated from the
   * transaction's own stored Sep31Info.Fields, so a status callback or client status API consumer
   * gets the actual metadata instead of one synthesized from the field name. Currently only
   * populated for SEP-31.
   */
  Map<String, AssetInfo.Field> requiredInfoUpdatesFields;
}
