package org.stellar.anchor.api.sep.sep12;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class Sep12CustomerRequestBase {
  String id;
  String account;
  String memo;

  @SerializedName("memo_type")
  String memoType;

  @SerializedName("transaction_id")
  String transactionId;
}
