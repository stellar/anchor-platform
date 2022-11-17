package org.stellar.anchor.api.sep.sep12;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;

/**
 * Refer to SEP-12.
 *
 * <p><<<<<<< Updated upstream
 *
 * <p><<<<<<< Updated upstream
 *
 * <p><a
 * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#request">...</a>
 * ======= <a
 * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#request">...</a>
 * >>>>>>> Stashed changes ======= <<<<<<< Updated upstream
 *
 * <p><a
 * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#request">...</a>
 * ======= <a
 * href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#request">...</a>
 * >>>>>>> Stashed changes >>>>>>> Stashed changes
 */
@Data
@Builder
public class Sep12GetCustomerRequest implements Sep12CustomerRequestBase {
  String id;
  String account;
  String memo;

  @SerializedName("memo_type")
  String memoType;

  String type;
  String lang;
}
