package org.stellar.anchor.api.platform;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.*;

/**
 * The transaction data in the response body of the GET /transactions/{id} endpoint of the Platform
 * API.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Platform%20API.yml">Platform
 *     API</a>
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class PlatformTransactionData {
  String id;
  Sep sep;
  Kind kind;
  SepTransactionStatus status;

  String type;

  @SerializedName("amount_expected")
  Amount amountExpected;

  @SerializedName("amount_in")
  Amount amountIn;

  @SerializedName("amount_out")
  Amount amountOut;

  @SerializedName("amount_fee")
  @Deprecated // ANCHOR-636
  Amount amountFee;

  @SerializedName("fee_details")
  FeeDetails feeDetails;

  @SerializedName("quote_id")
  String quoteId;

  @SerializedName("started_at")
  Instant startedAt;

  @SerializedName("updated_at")
  Instant updatedAt;

  @SerializedName("completed_at")
  Instant completedAt;

  @SerializedName("user_action_required_by")
  Instant userActionRequiredBy;

  @SerializedName("transfer_received_at")
  Instant transferReceivedAt;

  String message;
  Refunds refunds;

  @SerializedName("stellar_transactions")
  List<StellarTransaction> stellarTransactions;

  @SerializedName("source_account")
  String sourceAccount;

  @SerializedName("destination_account")
  String destinationAccount;

  @SerializedName("external_transaction_id")
  String externalTransactionId;

  @SerializedName("memo")
  String memo;

  @SerializedName("memo_type")
  String memoType;

  @SerializedName("refund_memo")
  String refundMemo;

  @SerializedName("refund_memo_type")
  String refundMemoType;

  @SerializedName("withdraw_anchor_account")
  String withdrawAnchorAccount;

  @SerializedName("client_domain")
  String clientDomain;

  @SerializedName("client_name")
  String clientName;

  Customers customers;
  StellarId creator;

  @SerializedName("required_info_message")
  String requiredInfoMessage;

  @SerializedName("required_info_updates")
  List<String> requiredInfoUpdates;

  @Deprecated
  @SerializedName("required_customer_info_message")
  String requiredCustomerInfoMessage;

  @Deprecated
  @SerializedName("required_customer_info_updates")
  List<String> requiredCustomerInfoUpdates;

  Map<String, InstructionField> instructions;

  public enum Sep {
    @SerializedName("6")
    SEP_6(6),
    @SerializedName("12")
    SEP_12(12),
    @SuppressWarnings("unused")
    @SerializedName("24")
    SEP_24(24),
    @SerializedName("31")
    SEP_31(31),
    @SerializedName("38")
    SEP_38(38);

    private final Integer sep;

    Sep(Integer sep) {
      this.sep = sep;
    }

    @JsonValue
    public Integer getSep() {
      return sep;
    }

    public static Sep from(String str) {
      for (Sep sep : values()) {
        if (sep.sep.toString().equals(str)) {
          return sep;
        }
      }
      throw new IllegalArgumentException("No matching constant for [" + str + "]");
    }
  }

  public enum Kind {
    @SuppressWarnings("unused")
    @SerializedName("undefined")
    UNDEFINED("undefined"),
    @SerializedName("receive")
    RECEIVE("receive"),
    @SerializedName("deposit")
    DEPOSIT("deposit"),
    @SerializedName("deposit-exchange")
    DEPOSIT_EXCHANGE("deposit-exchange"),
    @SerializedName("withdrawal")
    WITHDRAWAL("withdrawal"),

    @SerializedName("withdrawal-exchange")
    WITHDRAWAL_EXCHANGE("withdrawal-exchange");

    public final String kind;

    Kind(String kind) {
      this.kind = kind;
    }

    @JsonValue
    public String getKind() {
      return kind;
    }

    public static Kind from(String str) {
      for (Kind kind : values()) {
        if (kind.kind.equals(str)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("No matching constant for [" + str + "]");
    }
  }
}
