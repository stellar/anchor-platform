package org.stellar.anchor.reference.event;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.platform.PatchTransactionRequest;
import org.stellar.anchor.api.platform.PatchTransactionsRequest;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.event.models.QuoteEvent;
import org.stellar.anchor.event.models.TransactionEvent;
import org.stellar.anchor.reference.client.PlatformApiClient;
import org.stellar.anchor.reference.config.AppSettings;
import org.stellar.anchor.util.Log;

@Component
public class AnchorEventProcessor {
  private final PlatformApiClient platformClient;

  AnchorEventProcessor(AppSettings appSettings, Gson gson) {
    this.platformClient = new PlatformApiClient(appSettings.getPlatformApiEndpoint());
  }

  public void handleQuoteEvent(QuoteEvent event) {
    Log.debug(String.format("Received quote event: %s", event));
    switch (event.getType()) {
      case "quote_created":
        break;
      default:
        Log.debug("error: anchor_platform_event - invalid message type '%s'%n", event.getType());
    }
  }

  public void handleTransactionEvent(TransactionEvent event) {
    Log.debug(String.format("Received transaction event: %s", event));
    switch (event.getType()) {
      case "transaction_created":
      case "transaction_status_changed":
        // TODO fix this - current code only handles payment received
        handleTransactionStatusChangedEvent(event);
        break;
      case "transaction_error":
      default:
        Log.debug("error: anchor_platform_event - invalid message type '%s'%n", event.getType());
    }
  }

  public void handleTransactionStatusChangedEvent(TransactionEvent event) {
    // NOTE: this code skips processing the received payment and just marks the
    // transaction as complete.
    Log.debug("Updating transaction: %s on Anchor Platform to 'complete'", event.getId());
    PatchTransactionsRequest txnRequest =
        PatchTransactionsRequest.builder()
            .records(
                List.of(
                    PatchTransactionRequest.builder()
                        .id(event.getId())
                        .status(TransactionEvent.Status.COMPLETED.status)
                        .amountFee(
                            new Amount(
                                event.getAmountFee().getAmount(), event.getAmountFee().getAsset()))
                        .amountOut(
                            new Amount(
                                event.getAmountOut().getAmount(), event.getAmountOut().getAsset()))
                        .build()))
            .build();

    try {
      platformClient.patchTransaction(txnRequest);
    } catch (IOException | AnchorException ex) {
      Log.errorEx(ex);
    }
  }
}
