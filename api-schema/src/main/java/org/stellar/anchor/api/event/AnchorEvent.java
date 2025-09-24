package org.stellar.anchor.api.event;

import com.google.gson.annotations.SerializedName;
import lombok.*;
import org.stellar.anchor.api.platform.GetQuoteResponse;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse;

/**
 * The internal event object is used for event notification. They should be mapped to the
 * appropriate schemas for the API and status callback use cases.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnchorEvent {
  Type type;
  String id;
  String sep;
  GetTransactionResponse transaction;
  GetQuoteResponse quote;
  Sep12GetCustomerResponse customer;

  public enum Type {
    @SerializedName("transaction_created")
    TRANSACTION_CREATED("transaction_created"),
    @SerializedName("transaction_status_changed")
    TRANSACTION_STATUS_CHANGED("transaction_status_changed"),
    @SerializedName("transaction_error")
    TRANSACTION_ERROR("transaction_error"),
    @SerializedName("quote_created")
    QUOTE_CREATED("quote_created"),
    @SerializedName("customer_updated")
    CUSTOMER_UPDATED("customer_updated");

    public final String type;

    Type(String type) {
      this.type = type;
    }
  }
}
