package org.stellar.anchor.api.sep;

import com.google.gson.annotations.SerializedName;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public enum SepTransactionStatus {
  @SerializedName("pending_anchor")
  PENDING_ANCHOR("pending_anchor", "processing"),
  @SerializedName("pending_trust")
  PENDING_TRUST("pending_trust", "waiting for a trustline to be established"),
  @SerializedName("pending_user")
  PENDING_USER("pending_user", "waiting on user action"),
  @SerializedName("pending_user_transfer_start")
  PENDING_USR_TRANSFER_START(
      "pending_user_transfer_start", "waiting on the user to transfer funds"),
  @SerializedName("pending_user_transfer_complete")
  PENDING_USR_TRANSFER_COMPLETE(
      "pending_user_transfer_complete", "the user has transferred the funds"),
  @SerializedName("incomplete")
  INCOMPLETE("incomplete", "incomplete"),
  @SerializedName("no_market")
  NO_MARKET("no_market", "no market for the asset"),
  @SerializedName("too_small")
  TOO_SMALL("too_small", "the transaction amount is too small"),
  @SerializedName("too_large")
  TOO_LARGE("too_large", "the transaction amount is too big"),
  @SerializedName("pending_sender")
  PENDING_SENDER("pending_sender", null),
  @SerializedName("pending_receiver")
  PENDING_RECEIVER("pending_receiver", null),
  @SerializedName("pending_transaction_info_update")
  PENDING_TRANSACTION_INFO_UPDATE(
      "pending_transaction_info_update", "waiting for more transaction information"),
  @SerializedName("pending_customer_info_update")
  PENDING_CUSTOMER_INFO_UPDATE(
      "pending_customer_info_update", "waiting for more customer information"),
  @SerializedName("completed")
  COMPLETED("completed", "complete"),
  @SerializedName("refunded")
  REFUNDED("refunded", "the deposit/withdrawal is fully refunded"),
  @SerializedName("expired")
  EXPIRED(
      "expired",
      " funds were never received by the anchor and the transaction is considered abandoned by the Sending Client. If a SEP-38 quote was specified when the transaction was initiated, the transaction should expire when the quote expires, otherwise anchors are responsible for determining when transactions are considered expired."),
  @SerializedName("error")
  ERROR("error", "error"),
  @SerializedName("pending_external")
  PENDING_EXTERNAL("pending_external", "waiting on an external entity"),
  @SerializedName("pending_stellar")
  PENDING_STELLAR("pending_stellar", "stellar is executing the transaction"),
  @SerializedName("on_hold")
  ON_HOLD(
      "on_hold",
      "deposit/withdrawal is currently on hold for additional checks after receiving user's funds");

  private final String status;
  private final String description;

  SepTransactionStatus(String status, String description) {
    this.status = status;
    this.description = description;
  }

  public static SepTransactionStatus from(String status) {

    for (SepTransactionStatus sts : values()) {
      if (Objects.equals(sts.status, status)) {
        return sts;
      }
    }
    throw new IllegalArgumentException("No matching constant for [" + status + "]");
  }

  public String toString() {
    return status;
  }

  public String getName() {
    return status;
  }

  public String getStatus() {
    return status;
  }

  @SuppressWarnings("unused")
  public String getDescription() {
    return description;
  }

  public static boolean isValid(String status) {
    return Arrays.stream(SepTransactionStatus.values())
        .anyMatch(e -> e.status.equalsIgnoreCase(status));
  }

  public static String mergeStatusesList(List<SepTransactionStatus> list) {
    return mergeStatusesList(list, "");
  }

  public static String mergeStatusesList(List<SepTransactionStatus> list, String escapeStr) {
    return list.stream()
        .map(x -> escapeStr + x.toString() + escapeStr)
        .collect(Collectors.joining(","));
  }
}
