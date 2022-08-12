package org.stellar.anchor.platform.service;

import static org.stellar.anchor.api.sep.SepTransactionStatus.ERROR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_RECEIVER;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_SENDER;
import static org.stellar.anchor.util.MathHelper.*;

import io.micrometer.core.instrument.Metrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.codec.DecoderException;
import org.springframework.stereotype.Component;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.event.EventPublishService;
import org.stellar.anchor.event.models.*;
import org.stellar.anchor.platform.payment.observer.PaymentListener;
import org.stellar.anchor.platform.payment.observer.circle.ObservedPayment;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.MemoType;

@Component
public class PaymentOperationToEventListener implements PaymentListener {
  final Sep31TransactionStore transactionStore;
  final EventPublishService eventService;

  PaymentOperationToEventListener(
      Sep31TransactionStore transactionStore, EventPublishService eventService) {
    this.transactionStore = transactionStore;
    this.eventService = eventService;
  }

  @Override
  public void onReceived(ObservedPayment payment) {
    // Check if payment is connected to a transaction
    if (Objects.toString(payment.getTransactionHash(), "").isEmpty()
        || Objects.toString(payment.getTransactionMemo(), "").isEmpty()) {
      return;
    }

    // Check if the payment contains the expected asset type
    if (!List.of("credit_alphanum4", "credit_alphanum12").contains(payment.getAssetType())) {
      // Asset type does not match
      Log.warn("Not an issued asset");
      return;
    }

    // Parse memo
    String memo = payment.getTransactionMemo();
    String memoType = payment.getTransactionMemoType();
    if (memoType.equals(MemoHelper.memoTypeAsString(MemoType.MEMO_HASH))) {
      try {
        memo = MemoHelper.convertHexToBase64(payment.getTransactionMemo());
      } catch (DecoderException ex) {
        Log.warn("Not a HEX string");
        Log.warnEx(ex);
      }
    }

    // Find a transaction matching the memo
    Sep31Transaction txn;
    try {
      txn = transactionStore.findByStellarMemo(memo);
    } catch (AnchorException e) {
      Log.error(
          String.format(
              "error finding transaction that matches the memo (%s).",
              payment.getTransactionMemo()));
      e.printStackTrace();
      return;
    }
    if (txn == null) {
      Log.info(
          String.format(
              "Not expecting any transaction with the memo %s.", payment.getTransactionMemo()));
      return;
    }

    // Compare asset code
    String paymentAssetName = "stellar:" + payment.getAssetName();
    if (!txn.getAmountInAsset().equals(paymentAssetName)) {
      Log.warn(
          String.format(
              "Payment asset %s does not match the expected asset %s",
              payment.getAssetCode(), txn.getAmountInAsset()));
      return;
    }

    // Check if the payment contains the expected amount (or greater)
    BigDecimal expectedAmount = decimal(txn.getAmountIn());
    BigDecimal gotAmount = decimal(payment.getAmount());
    if (gotAmount.compareTo(expectedAmount) < 0) {
      Log.warn(
          String.format(
              "Payment amount %s is smaller than the expected amount %s",
              payment.getAmount(), txn.getAmountIn()));
      updateTransactionStatusTo(ERROR, txn, payment);
      return;
    }

    // Set the transaction status.
    TransactionEvent.Status oldStatus = TransactionEvent.Status.from(txn.getStatus());
    TransactionEvent.Status newStatus = TransactionEvent.Status.PENDING_RECEIVER;
    TransactionEvent.StatusChange statusChange =
        new TransactionEvent.StatusChange(oldStatus, newStatus);
    if (txn.getStatus().equals(PENDING_SENDER.toString())) {
      updateTransactionStatusTo(PENDING_RECEIVER, txn, payment);
    }

    // send to the event queue
    TransactionEvent event = receivedPaymentToEvent(txn, payment, statusChange);
    sendToQueue(event);
    Metrics.counter(AnchorMetrics.PAYMENT_RECEIVED.toString(), "asset", payment.getAssetName())
        .increment(Double.parseDouble(payment.getAmount()));
  }

  @Override
  public void onSent(ObservedPayment payment) {
    // noop
  }

  private void sendToQueue(TransactionEvent event) {
    eventService.publish(event);
    Log.info("Sent to event queue" + GsonUtils.getInstance().toJson(event));
  }

  TransactionEvent receivedPaymentToEvent(
      Sep31Transaction txn, ObservedPayment payment, TransactionEvent.StatusChange statusChange) {
    TransactionEvent event =
        TransactionEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .type(TransactionEvent.Type.TRANSACTION_STATUS_CHANGED)
            .id(txn.getId())
            .sep(TransactionEvent.Sep.SEP_31)
            .kind(TransactionEvent.Kind.RECEIVE)
            .status(statusChange.getTo())
            .statusChange(statusChange)
            .amountExpected(new Amount(txn.getAmountExpected(), txn.getAmountInAsset()))
            .amountIn(new Amount(payment.getAmount(), txn.getAmountInAsset()))
            .amountOut(new Amount(txn.getAmountOut(), txn.getAmountOutAsset()))
            // TODO: fix PATCH transaction fails if getAmountOut is null?
            .amountFee(new Amount(txn.getAmountFee(), txn.getAmountFeeAsset()))
            .quoteId(txn.getQuoteId())
            .startedAt(txn.getStartedAt())
            .updatedAt(txn.getUpdatedAt())
            .completedAt(null)
            .transferReceivedAt(txn.getTransferReceivedAt())
            .message("Incoming payment for SEP-31 transaction")
            .refunds(null)
            .stellarTransactions(
                new StellarTransaction[] {
                  StellarTransaction.builder()
                      .id(payment.getTransactionHash())
                      .memo(txn.getStellarMemo())
                      .memoType(txn.getStellarMemoType())
                      .createdAt(txn.getTransferReceivedAt())
                      .envelope(payment.getTransactionEnvelope())
                      .payments(
                          new Payment[] {
                            Payment.builder()
                                .operationId(payment.getId())
                                .sourceAccount(payment.getFrom())
                                .destinationAccount(payment.getTo())
                                .amount(new Amount(payment.getAmount(), payment.getAssetName()))
                                .build()
                          })
                      .build()
                })
            .externalTransactionId(payment.getExternalTransactionId())
            .custodialTransactionId(null)
            .sourceAccount(payment.getFrom())
            .destinationAccount(payment.getTo())
            .customers(txn.getCustomers())
            .creator(txn.getCreator())
            .build();
    return event;
  }

  void updateTransactionStatusTo(
      SepTransactionStatus newStatus, Sep31Transaction txn, ObservedPayment payment) {
    // parse payment creation time
    Instant paymentTime = null;
    try {
      paymentTime = DateTimeFormatter.ISO_INSTANT.parse(payment.getCreatedAt(), Instant::from);
    } catch (DateTimeParseException | NullPointerException ex) {
      Log.error(
          String.format("error parsing payment.getCreatedAt() (%s).", payment.getCreatedAt()));
      ex.printStackTrace();
    }

    // update the transaction differently based on the new status
    switch (newStatus) {
      case ERROR:
        if (paymentTime != null) {
          txn.setUpdatedAt(paymentTime);
        }
        txn.setStatus(ERROR.getName());
        saveTransaction(txn);
        Metrics.counter(AnchorMetrics.SEP31_TRANSACTION.toString(), "status", ERROR.getName())
            .increment();
        break;

      case PENDING_RECEIVER:
        if (paymentTime != null) {
          txn.setUpdatedAt(paymentTime);
          txn.setTransferReceivedAt(paymentTime);
        }
        txn.setStatus(PENDING_RECEIVER.toString());
        txn.setStellarTransactionId(payment.getTransactionHash());
        saveTransaction(txn);
        Metrics.counter("sep31.transaction", "status", PENDING_RECEIVER.toString()).increment();

      default:
        Log.errorF("Unsupported new status {}.", newStatus);
    }
  }

  void saveTransaction(Sep31Transaction txn) {
    try {
      transactionStore.save(txn);
    } catch (SepException ex) {
      Log.errorEx(ex);
    }
  }
}
