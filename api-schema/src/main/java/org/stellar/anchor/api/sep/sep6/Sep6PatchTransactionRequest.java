package org.stellar.anchor.api.sep.sep6;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The request sent to PATCH /transactions/{id} of SEP-6.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0006.md#patch-transactions">Refer
 *     to SEP-6</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Sep6PatchTransactionRequest {
  /** Set from the path, never from the request body. */
  String id;

  Map<String, String> transaction;
}
