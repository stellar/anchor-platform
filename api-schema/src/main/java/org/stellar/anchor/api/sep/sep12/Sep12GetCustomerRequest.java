package org.stellar.anchor.api.sep.sep12;

import lombok.Data;
import lombok.experimental.SuperBuilder;

/**
 * The request body of the GET /customer endpoint of SEP-12.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#fields">Refer
 *     to SEP-12</a>
 */
@Data
@SuperBuilder
public class Sep12GetCustomerRequest extends Sep12CustomerRequestBase {
  String type;
  String lang;
}
