package org.stellar.anchor.platform.utils;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.StellarPayment;
import org.stellar.anchor.api.shared.StellarTransaction;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep31Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.observer.ObservedPayment;
import org.stellar.anchor.util.Log;

public class PaymentsUtil {

  public static void addStellarTransaction(JdbcSepTransaction txn, LedgerTransaction ledgerTxn) {

    List<ObservedPayment> payments = getObservedPayments(ledgerTxn);

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
              .id(ledgerTxn.getHash())
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
      txn.setStellarTransactionId(ledgerTxn.getHash());
    }
  }

  private static List<ObservedPayment> getObservedPayments(LedgerTransaction ledgerTxn) {
    return ledgerTxn.getOperations().stream()
        .map(
            operation ->
                switch (operation.getType()) {
                  case PAYMENT -> ObservedPayment.from(ledgerTxn, operation.getPaymentOperation());
                  case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND ->
                      ObservedPayment.from(ledgerTxn, operation.getPathPaymentOperation());
                  default -> null;
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
      return null;
    }
  }
}
