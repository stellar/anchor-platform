package org.stellar.anchor.platform.utils;

import static java.util.stream.Collectors.toList;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.StellarPayment;
import org.stellar.anchor.api.shared.StellarTransaction;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPayment;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep31Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.util.AssetHelper;

public class PaymentHelper {

  public static LedgerPayment getLedgerPayment(LedgerOperation ledgerOperation) {
    return switch (ledgerOperation.getType()) {
      case PAYMENT -> ledgerOperation.getPaymentOperation();
      case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND ->
          ledgerOperation.getPathPaymentOperation();
      default -> null;
    };
  }

  public static void addStellarTransaction(LedgerTransaction ledgerTxn, JdbcSepTransaction sepTxn) {
    String memo = null;
    String memoType = null;

    switch (Sep.from(sepTxn.getProtocol())) {
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) sepTxn;
        memo = txn24.getMemo();
        memoType = txn24.getMemoType();
        break;
      case SEP_31:
        JdbcSep31Transaction txn31 = (JdbcSep31Transaction) sepTxn;
        memo = txn31.getStellarMemo();
        memoType = txn31.getStellarMemoType();
        break;
      case SEP_6:
        JdbcSep6Transaction sep6 = (JdbcSep6Transaction) sepTxn;
        memo = sep6.getMemo();
        memoType = sep6.getMemoType();
        break;
    }

    StellarTransaction stellarTransaction =
        StellarTransaction.builder()
            .id(ledgerTxn.getHash())
            .createdAt(Optional.ofNullable(ledgerTxn.getCreatedAt()).orElse(Instant.now()))
            .memo(memo)
            .memoType(memoType)
            .envelope(ledgerTxn.getEnvelopeXdr())
            .payments(
                ledgerTxn.getOperations().stream()
                    .map(PaymentHelper::getLedgerPayment)
                    .filter(Objects::nonNull)
                    .map(
                        payment ->
                            StellarPayment.builder()
                                .id(payment.getId())
                                .amount(
                                    new Amount(
                                        AssetHelper.fromXdrAmount(payment.getAmount()),
                                        AssetHelper.getSep11AssetName(payment.getAsset())))
                                .sourceAccount(payment.getFrom())
                                .destinationAccount(payment.getTo())
                                .paymentType(
                                    (payment instanceof LedgerPaymentOperation)
                                        ? StellarPayment.Type.PAYMENT
                                        : StellarPayment.Type.PATH_PAYMENT)
                                .build())
                    .collect(toList()))
            .build();

    if (sepTxn.getStellarTransactions() == null) {
      sepTxn.setStellarTransactions(List.of(stellarTransaction));
    } else {
      sepTxn.getStellarTransactions().add(stellarTransaction);
    }

    sepTxn.setTransferReceivedAt(
        Optional.ofNullable(ledgerTxn.getCreatedAt()).orElse(Instant.now()));
    sepTxn.setStellarTransactionId(ledgerTxn.getHash());
  }
}
