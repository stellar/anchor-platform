package org.stellar.anchor.api.platform;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.*;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class PlatformTransactionData {
  String id;
  Sep sep;
  Kind kind;
  SepTransactionStatus status;

  @SerializedName("amount_expected")
  Amount amountExpected;

  @SerializedName("amount_in")
  Amount amountIn;

  @SerializedName("amount_out")
  Amount amountOut;

  @SerializedName("amount_fee")
  Amount amountFee;

  @SerializedName("quote_id")
  String quoteId;

  @SerializedName("started_at")
  Instant startedAt;

  @SerializedName("updated_at")
  Instant updatedAt;

  @SerializedName("completed_at")
  Instant completedAt;

  @SerializedName("transfer_received_at")
  Instant transferReceivedAt;

  String message;
  Refunds refunds;

  @SerializedName("stellar_transactions")
  List<StellarTransaction> stellarTransactions;

  @SerializedName("stellar_transaction_id")
  String stellarTransactionId;

  @SerializedName("external_transaction_id")
  String externalTransactionId;

  // TODO: review the use case of the custodialTransactionId
  @SerializedName("custodial_transaction_id")
  String custodialTransactionId;

  Customers customers;
  StellarId creator;

  public enum Sep {
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
  }

  public enum Kind {
    @SuppressWarnings("unused")
    @SerializedName("undefined")
    UNDEFINED("undefined"),
    @SerializedName("receive")
    RECEIVE("receive"),
    @SerializedName("deposit")
    DEPOSIT("deposit"),
    @SerializedName("withdrawal")
    WITHDRAWAL("withdrawal");

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
