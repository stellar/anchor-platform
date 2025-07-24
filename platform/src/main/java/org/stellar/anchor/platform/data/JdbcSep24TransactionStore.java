package org.stellar.anchor.platform.data;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.sep.sep24.GetTransactionsRequest;
import org.stellar.anchor.sep24.Sep24RefundPayment;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.util.DateUtil;
import org.stellar.anchor.util.TransactionsParams;

public class JdbcSep24TransactionStore implements Sep24TransactionStore {
  final JdbcSep24TransactionRepo txnRepo;

  public JdbcSep24TransactionStore(JdbcSep24TransactionRepo txnRepo) {
    this.txnRepo = txnRepo;
  }

  @Override
  public Sep24Transaction newInstance() {
    return new JdbcSep24Transaction();
  }

  @Override
  public Sep24Refunds newRefunds() {
    return new JdbcSep24Refunds();
  }

  @Override
  public Sep24RefundPayment newRefundPayment() {
    return new JdbcSep24RefundPayment();
  }

  @Override
  public Sep24Transaction findByTransactionId(String transactionId) {
    return txnRepo.findOneByTransactionId(transactionId);
  }

  @Override
  public Sep24Transaction findByStellarTransactionId(String stellarTransactionId) {
    return txnRepo.findOneByStellarTransactionId(stellarTransactionId);
  }

  @Override
  public Sep24Transaction findByExternalTransactionId(String externalTransactionId) {
    return txnRepo.findOneByExternalTransactionId(externalTransactionId);
  }

  public JdbcSep24Transaction findOneByToAccountAndMemoAndStatus(
      String toAccount, String memo, String status) {
    Optional<JdbcSep24Transaction> optTxn =
        Optional.ofNullable(txnRepo.findOneByToAccountAndMemoAndStatus(toAccount, memo, status));
    return optTxn.orElse(null);
  }

  public JdbcSep24Transaction findOneByToAccountAndFromAccountAndStatus(
      String toAccount, String fromAccount, String status) {
    Optional<JdbcSep24Transaction> optTxn =
        Optional.ofNullable(
            txnRepo.findOneByToAccountAndFromAccountAndStatus(toAccount, fromAccount, status));
    return optTxn.orElse(null);
  }

  public JdbcSep24Transaction findFirstByToAccountAndFromAccountAndStatusOrderByStartedAtDesc(
      String toAccount, String fromAccount, String status) {
    Optional<JdbcSep24Transaction> optTxn =
        Optional.ofNullable(
            txnRepo.findFirstByToAccountAndFromAccountAndStatusOrderByStartedAtDesc(
                toAccount, fromAccount, status));
    return optTxn.orElse(null);
  }

  @Override
  public List<Sep24Transaction> findTransactions(
      String accountId, String accountMemo, GetTransactionsRequest tr)
      throws SepValidationException {

    if (accountMemo != null) accountId = accountId + ":" + accountMemo;

    List<Sep24Transaction> txns =
        txnRepo.findByWebAuthAccountAndRequestAssetCodeOrderByStartedAtDesc(
            accountId, tr.getAssetCode());

    // TODO: This should be replaced by Couchbase query
    int limit = Integer.MAX_VALUE;
    if (tr.getLimit() != null && tr.getLimit() > 0) {
      limit = tr.getLimit();
    }

    Instant noOlderThan = Instant.EPOCH;
    Instant olderThan = Instant.now();

    if (tr.getPagingId() != null) {
      Sep24Transaction txn = txnRepo.findOneByTransactionId(tr.getPagingId());
      if (txn != null) {
        olderThan = txn.getStartedAt();
      }
    }

    if (tr.getNoOlderThan() != null) {
      try {
        noOlderThan = DateUtil.fromISO8601UTC(tr.getNoOlderThan());
      } catch (DateTimeParseException dtpex) {
        throw new SepValidationException(
            String.format("invalid no_older_than field: %s", tr.getNoOlderThan()));
      }
    }

    final Instant finalNoOlderThan = noOlderThan;
    final Instant finalOlderThan = olderThan;

    txns =
        txns.stream()
            .filter(txn -> (tr.getKind() == null || tr.getKind().equals(txn.getKind())))
            .filter(txn -> (txn.getStartedAt().isAfter(finalNoOlderThan)))
            .filter(txn -> (txn.getStartedAt().isBefore(finalOlderThan)))
            .limit(limit)
            .collect(Collectors.toList());

    return txns;
  }

  @Override
  public Sep24Transaction save(Sep24Transaction sep24Transaction) throws SepException {
    if (!(sep24Transaction instanceof JdbcSep24Transaction)) {
      throw new SepException(
          sep24Transaction.getClass() + "  is not a sub-type of " + JdbcSep24Transaction.class);
    }
    JdbcSep24Transaction txn = (JdbcSep24Transaction) sep24Transaction;
    txn.setId(txn.getTransactionId());
    return txnRepo.save(txn);
  }

  @Override
  public List<? extends Sep24Transaction> findTransactions(TransactionsParams params) {
    return txnRepo.findAllTransactions(params, JdbcSep24Transaction.class);
  }
}
