package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;

public enum CustodyTransactionStatus {
  @SerializedName("created")
  CREATED("created"),
  @SerializedName("submitted")
  SUBMITTED("submitted"),
  @SerializedName("completed")
  COMPLETED("completed"),
  @SerializedName("failed")
  FAILED("failed");

  private final String status;

  CustodyTransactionStatus(String status) {
    this.status = status;
  }

  public String toString() {
    return status;
  }

  public static CustodyTransactionStatus from(String str) {
    for (CustodyTransactionStatus status : values()) {
      if (status.status.equals(str)) {
        return status;
      }
    }
    throw new IllegalArgumentException("No matching constant for [" + str + "]");
  }
}
