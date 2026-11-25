package org.stellar.anchor.api.sep.sep31;

import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest.Sep31TxnFields;
import org.stellar.anchor.config.PII;

/**
 * The request sent to PATCH /transaction/{id} of SEP-31.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0031.md#fields">Refer
 *     to SEP-31</a>
 */
@Data
@Builder
public class Sep31PatchTransactionRequest {
  String id;

  @PII Sep31TxnFields fields;
}
