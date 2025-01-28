package org.stellar.anchor.platform.utils;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static org.stellar.anchor.util.Log.error;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.StellarPayment;
import org.stellar.anchor.api.shared.StellarTransaction;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep31Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.observer.ObservedPayment;
import org.stellar.anchor.util.Log;
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;

public class PaymentsUtil {

  public static void addStellarTransaction(
      JdbcSepTransaction txn, String stellarTransactionId, List<OperationResponse> operations) {

    List<ObservedPayment> payments = getObservedPayments(operations);

    if (!payments.isEmpty()) {
      ObservedPayment firstPayment = payments.get(0);
      ObservedPayment lastPayment = payments.get(payments.size() - 1);

      String memo = null;
      String memoType = null;

      switch (Sep.from(txn.getProtocol())) {
        case SEP_24:
          JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
          memo = txn24.getMemo();
          memoType = txn24.getMemoType();
          break;
        case SEP_31:
          JdbcSep31Transaction txn31 = (JdbcSep31Transaction) txn;
          memo = txn31.getStellarMemo();
          memoType = txn31.getStellarMemoType();
          break;
      }

      StellarTransaction stellarTransaction =
          StellarTransaction.builder()
              .id(stellarTransactionId)
              .createdAt(
                  Optional.ofNullable(parsePaymentTime(firstPayment.getCreatedAt()))
                      .orElse(Instant.now()))
              .memo(memo)
              .memoType(memoType)
              .envelope(firstPayment.getTransactionEnvelope())
              .payments(
                  payments.stream()
                      .map(
                          payment ->
                              StellarPayment.builder()
                                  .id(payment.getId())
                                  .amount(new Amount(payment.getAmount(), payment.getAssetName()))
                                  .sourceAccount(payment.getFrom())
                                  .destinationAccount(payment.getTo())
                                  .paymentType(
                                      payment.getType() == ObservedPayment.Type.PAYMENT
                                          ? StellarPayment.Type.PAYMENT
                                          : StellarPayment.Type.PATH_PAYMENT)
                                  .build())
                      .collect(toList()))
              .build();

      if (txn.getStellarTransactions() == null) {
        txn.setStellarTransactions(List.of(stellarTransaction));
      } else {
        txn.getStellarTransactions().add(stellarTransaction);
      }

      txn.setTransferReceivedAt(
          Optional.ofNullable(parsePaymentTime(lastPayment.getCreatedAt())).orElse(Instant.now()));
      txn.setStellarTransactionId(stellarTransactionId);
    }
  }

  private static List<ObservedPayment> getObservedPayments(List<OperationResponse> payments) {
    return payments.stream()
        .flatMap(
            operation -> {
              try {
                if (operation instanceof PaymentOperationResponse) {
                  return Stream.of(
                      ObservedPayment.fromPaymentOperationResponse(
                          (PaymentOperationResponse) operation));
                } else if (operation instanceof PathPaymentBaseOperationResponse) {
                  return Stream.of(
                      ObservedPayment.fromPathPaymentOperationResponse(
                          (PathPaymentBaseOperationResponse) operation));
                } else if (operation instanceof InvokeHostFunctionOperationResponse) {
                  return ObservedPayment.fromInvokeHostFunctionOperationResponse(
                      (InvokeHostFunctionOperationResponse) operation)
                      .stream();
                }
              } catch (SepException e) {
                error("Failed to parse operation response", e);
              }
              return null;
            })
        .filter(Objects::nonNull)
        .sorted(comparing(ObservedPayment::getCreatedAt))
        .collect(toList());
  }

  private static Instant parsePaymentTime(String paymentTimeStr) {
    try {
      return DateTimeFormatter.ISO_INSTANT.parse(paymentTimeStr, Instant::from);
    } catch (DateTimeParseException | NullPointerException ex) {
      Log.errorF("Error parsing paymentTimeStr {}.", paymentTimeStr);
      ex.printStackTrace();
      return null;
    }
  }
}
