package org.stellar.anchor.platform.data;

import static org.stellar.anchor.util.TransactionQueryLimits.DEFAULT_LIMIT;
import static org.stellar.anchor.util.TransactionQueryLimits.MAX_LIMIT;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

  public JdbcSep24Transaction findOneByWithdrawAnchorAccountAndMemoAndStatus(
      String toAccount, String memo, String status) {
    Optional<JdbcSep24Transaction> optTxn =
        Optional.ofNullable(
            txnRepo.findOneByWithdrawAnchorAccountAndMemoAndStatus(toAccount, memo, status));
    return optTxn.orElse(null);
  }

  @Override
  public List<Sep24Transaction> findTransactions(
      String accountId, String accountMemo, GetTransactionsRequest tr)
      throws SepValidationException {
    int limit = DEFAULT_LIMIT;
    if (tr.getLimit() != null && tr.getLimit() > 0) {
      limit = Math.min(tr.getLimit(), MAX_LIMIT);
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

    Pageable pageable = PageRequest.of(0, limit);

    if (accountMemo == null) {
      return new ArrayList<>(
          txnRepo.findTransactionsWithFilters(
              accountId, tr.getAssetCode(), tr.getKind(), noOlderThan, olderThan, pageable));
    } else {
      List<JdbcSep24Transaction> txns =
          txnRepo.findTransactionsWithMemoAndFilters(
              accountId,
              accountMemo,
              tr.getAssetCode(),
              tr.getKind(),
              noOlderThan,
              olderThan,
              pageable);
      // Backward compatibility for legacy rows that may have stored account:memo in
      // web_auth_account and left web_auth_account_memo empty.
      if (txns.isEmpty()) {
        txns =
            txnRepo.findTransactionsWithFilters(
                accountId + ":" + accountMemo,
                tr.getAssetCode(),
                tr.getKind(),
                noOlderThan,
                olderThan,
                pageable);
      }
      return new ArrayList<>(txns);
    }
  }

  @Override
  public Sep24Transaction save(Sep24Transaction sep24Transaction) throws SepException {
    if (!(sep24Transaction instanceof JdbcSep24Transaction txn)) {
      throw new SepException(
          sep24Transaction.getClass() + "  is not a sub-type of " + JdbcSep24Transaction.class);
    }
    txn.setId(txn.getTransactionId());
    return txnRepo.save(txn);
  }

  @Override
  public List<? extends Sep24Transaction> findTransactions(TransactionsParams params) {
    return txnRepo.findAllTransactions(params, JdbcSep24Transaction.class);
  }
}
