package org.stellar.anchor.api.shared;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StellarTransaction {
  String id;
  String memo;

  @SerializedName("memo_type")
  String memoType;

  @SerializedName("created_at")
  Instant createdAt;

  String envelope;

  List<StellarPayment> payments;
}
