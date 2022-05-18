package org.stellar.anchor.platform.service;

import static org.stellar.anchor.api.sep.SepTransactionStatus.ERROR;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
import org.stellar.anchor.platform.data.JdbcSep31TransactionStore;
import org.stellar.anchor.platform.paymentobserver.ObservedPayment;
import org.stellar.anchor.platform.paymentobserver.PaymentListener;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.MemoType;

@Component
public class PaymentOperationToEventListener implements PaymentListener {
  final JdbcSep31TransactionStore transactionStore;
  final EventPublishService eventService;

  PaymentOperationToEventListener(
      JdbcSep31TransactionStore transactionStore, EventPublishService eventService) {
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
    if (!txn.getAmountInAsset().equals(payment.getAssetCode())) {
      Log.warn(
          String.format(
              "Payment asset %s does not match the expected asset %s",
              payment.getAssetCode(), txn.getAmountInAsset()));
      return;
    }

    // Check if the payment contains the expected amount (or greater)
    BigDecimal expectedAmount = new BigDecimal(txn.getAmountIn());
    BigDecimal gotAmount = new BigDecimal(payment.getAmount());
    if (gotAmount.compareTo(expectedAmount) < 0) {
      Log.warn(
          String.format(
              "Payment amount %s is smaller than the expected amount %s",
              payment.getAmount(), txn.getAmountIn()));
      txn.setStatus(ERROR.getName());
      saveTransaction(txn);
      return;
    }

    // Set the transaction status.
    TransactionEvent event = receivedPaymentToEvent(txn, payment);
    if (txn.getStatus().equals(SepTransactionStatus.PENDING_SENDER.toString())) {
      txn.setStatus(SepTransactionStatus.PENDING_RECEIVER.toString());
      txn.setStellarTransactionId(payment.getTransactionHash());
      try {
        transactionStore.save(txn);
      } catch (SepException ex) {
        Log.errorEx(ex);
      }
    }
    // send to the event queue
    sendToQueue(event);
  }

  @Override
  public void onSent(ObservedPayment payment) {
    // noop
  }

  private void sendToQueue(TransactionEvent event) {
    eventService.publish(event);
    Log.info("Sent to event queue" + GsonUtils.getInstance().toJson(event));
  }

  TransactionEvent receivedPaymentToEvent(Sep31Transaction txn, ObservedPayment payment) {
    Instant paymentTime =
        DateTimeFormatter.ISO_INSTANT.parse(payment.getCreatedAt(), Instant::from);

    TransactionEvent.Status oldStatus = TransactionEvent.Status.from(txn.getStatus());
    TransactionEvent.Status newStatus = TransactionEvent.Status.PENDING_RECEIVER;
    TransactionEvent.StatusChange statusChange =
        new TransactionEvent.StatusChange(oldStatus, newStatus);

    StellarId senderStellarId =
        StellarId.builder()
            .account(payment.getFrom())
            .memo(txn.getStellarMemo())
            .memoType(txn.getStellarMemoType())
            .build();
    StellarId receiverStellarId = StellarId.builder().account(payment.getTo()).build();

    TransactionEvent event =
        TransactionEvent.builder()
            .eventId(UUID.randomUUID().toString())
            // TODO: update to TRANSACTION_STATUS_CHANGED:
            .type(TransactionEvent.Type.TRANSACTION_STATUS_CHANGED)
            .id(txn.getId())
            .sep(TransactionEvent.Sep.SEP_31)
            .kind(TransactionEvent.Kind.RECEIVE)
            .status(newStatus)
            .statusChange(statusChange)
            .amountExpected(new Amount(txn.getAmountIn(), txn.getAmountInAsset()))
            .amountIn(new Amount(payment.getAmount(), payment.getAssetCode()))
            .amountOut(new Amount(txn.getAmountOut(), txn.getAmountOutAsset()))
            // TODO: fix PATCH transaction fails if getAmountOut is null?
            .amountFee(new Amount(txn.getAmountFee(), txn.getAmountFeeAsset()))
            .quoteId(txn.getQuoteId())
            .startedAt(txn.getStartedAt())
            .updatedAt(paymentTime)
            .completedAt(null)
            .transferReceivedAt(paymentTime)
            .message("Incoming payment for SEP-31 transaction")
            .refund(null)
            .stellarTransactions(
                new StellarTransaction[] {
                  StellarTransaction.builder()
                      .id(payment.getTransactionHash())
                      .memo(txn.getStellarMemo())
                      .memoType(txn.getStellarMemoType())
                      .createdAt(paymentTime)
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
            .customers(new Customers(senderStellarId, receiverStellarId))
            .creator(senderStellarId)
            .build();
    return event;
  }

  void saveTransaction(Sep31Transaction txn) {
    try {
      transactionStore.save(txn);
    } catch (SepException ex) {
      Log.errorEx(ex);
    }
  }
}
