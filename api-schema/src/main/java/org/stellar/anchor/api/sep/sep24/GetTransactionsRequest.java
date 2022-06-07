package org.stellar.anchor.api.sep.sep24;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class GetTransactionsRequest {
  @SerializedName("asset_code")
  String assetCode;

  @SerializedName("no_older_than")
  String noOlderThan;

  Integer limit;

  @SerializedName("paging_id")
  String pagingId;

  String kind;

  public static GetTransactionsRequest of(
      String assetCode, String kind, Integer limit, String noOlderThan, String pagingId) {
    GetTransactionsRequest r = new GetTransactionsRequest();
    r.assetCode = assetCode;
    r.kind = kind;
    r.limit = limit;
    r.noOlderThan = noOlderThan;
    r.pagingId = pagingId;
    return r;
  }
}
