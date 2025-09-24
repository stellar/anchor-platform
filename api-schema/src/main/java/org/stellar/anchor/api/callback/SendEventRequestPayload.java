package org.stellar.anchor.api.callback;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.platform.CustomerUpdatedResponse;
import org.stellar.anchor.api.platform.GetQuoteResponse;
import org.stellar.anchor.api.platform.GetTransactionResponse;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SendEventRequestPayload {
  GetTransactionResponse transaction;
  GetQuoteResponse quote;
  CustomerUpdatedResponse customer;

  /**
   * Creates a SendEventRequestPayload from an AnchorEvent.
   *
   * @param event
   * @return a SendEventRequestPayload
   */
  public static SendEventRequestPayload from(AnchorEvent event) {
    SendEventRequestPayload payload = new SendEventRequestPayload();
    switch (event.getType()) {
      case CUSTOMER_UPDATED:
        payload.setCustomer(
            event.getCustomer() != null
                ? CustomerUpdatedResponse.builder().id(event.getCustomer().getId()).build()
                : null);
      case QUOTE_CREATED:
        payload.setQuote(event.getQuote());
      case TRANSACTION_CREATED:
        payload.setTransaction(event.getTransaction());
      case TRANSACTION_ERROR:
        payload.setTransaction(event.getTransaction());
      case TRANSACTION_STATUS_CHANGED:
        payload.setTransaction(event.getTransaction());
    }
    return payload;
  }
}
