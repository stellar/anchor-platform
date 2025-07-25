package org.stellar.anchor.platform.data;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.sep.sep6.GetTransactionsRequest;
import org.stellar.anchor.api.shared.RefundPayment;
import org.stellar.anchor.api.shared.Refunds;
import org.stellar.anchor.sep6.*;
import org.stellar.anchor.util.DateUtil;
import org.stellar.anchor.util.TransactionsParams;

public class JdbcSep6TransactionStore implements Sep6TransactionStore {
  private final JdbcSep6TransactionRepo transactionRepo;

  public JdbcSep6TransactionStore(JdbcSep6TransactionRepo transactionRepo) {
    this.transactionRepo = transactionRepo;
  }

  @Override
  public Sep6Transaction newInstance() {
    return new JdbcSep6Transaction();
  }

  @Override
  public Refunds newRefunds() {
    return new Refunds();
  }

  @Override
  public RefundPayment newRefundPayment() {
    return new RefundPayment();
  }

  @Override
  public Sep6Transaction findByTransactionId(String transactionId) {
    return transactionRepo.findById(transactionId).orElse(null);
  }

  @Override
  public Sep6Transaction findByStellarTransactionId(String stellarTransactionId) {
    return transactionRepo.findOneByStellarTransactionId(stellarTransactionId);
  }

  @Override
  public Sep6Transaction findByExternalTransactionId(String externalTransactionId) {
    return transactionRepo.findOneByExternalTransactionId(externalTransactionId);
  }

  @Override
  public List<Sep6Transaction> findTransactions(
      String accountId, String accountMemo, GetTransactionsRequest request)
      throws SepValidationException {
    List<Sep6Transaction> txns;
    if (accountMemo == null) {
      txns =
          transactionRepo.findByWebAuthAccountAndRequestAssetCodeOrderByStartedAtDesc(
              accountId, request.getAssetCode());
    } else {
      txns =
          transactionRepo
              .findByWebAuthAccountAndWebAuthAccountMemoAndRequestAssetCodeOrderByStartedAtDesc(
                  accountId, accountMemo, request.getAssetCode());
    }

    int limit = Integer.MAX_VALUE;
    if (request.getLimit() != null && request.getLimit() > 0) {
      limit = request.getLimit();
    }

    final Instant noOlderThan;
    final Instant olderThan;

    if (request.getPagingId() != null) {
      Sep6Transaction txn = transactionRepo.findOneByTransactionId(request.getPagingId());
      if (txn != null) {
        olderThan = txn.getStartedAt();
      } else {
        throw new SepValidationException(
            String.format("invalid paging_id field: %s", request.getPagingId()));
      }
    } else {
      olderThan = Instant.now();
    }

    if (request.getNoOlderThan() != null) {
      try {
        noOlderThan = DateUtil.fromISO8601UTC(request.getNoOlderThan());
      } catch (DateTimeParseException e) {
        throw new SepValidationException(
            String.format("invalid no_older_than field: %s", request.getNoOlderThan()));
      }
    } else {
      noOlderThan = Instant.EPOCH;
    }

    return txns.stream()
        .filter(txn -> request.getKind() == null || request.getKind().equals(txn.getKind()))
        .filter(txn -> txn.getStartedAt().isAfter(noOlderThan))
        .filter(txn -> txn.getStartedAt().isBefore(olderThan))
        .limit(limit)
        .collect(Collectors.toList());
  }

  @Override
  public Sep6Transaction save(Sep6Transaction transaction) throws SepException {
    if (!(transaction instanceof JdbcSep6Transaction txn)) {
      throw new SepException(
          transaction.getClass() + " is not a sub-type of " + JdbcSep6Transaction.class);
    }
      txn.setUpdatedAt(Instant.now());

    return transactionRepo.save(txn);
  }

  @Override
  public List<? extends Sep6Transaction> findTransactions(TransactionsParams params) {
    return transactionRepo.findAllTransactions(params, JdbcSep6Transaction.class);
  }

  @Override
  public JdbcSep6Transaction findOneByWithdrawAnchorAccountAndMemoAndStatus(
      String withdrawAnchorAccount, String memo, String status) {
    return transactionRepo.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        withdrawAnchorAccount, memo, status);
  }

  @Override
  public JdbcSep6Transaction
      findFirstByWithdrawAnchorAccountAndFromAccountAndStatusOrderByStartedAtDesc(
          String toAccount, String fromAccount, String status) {
    return transactionRepo
        .findFirstByWithdrawAnchorAccountAndFromAccountAndStatusOrderByStartedAtDesc(
            toAccount, fromAccount, status);
  }
}
