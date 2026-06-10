package org.stellar.anchor.sep6;

import java.util.List;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.sep6.GetTransactionsRequest;
import org.stellar.anchor.api.shared.RefundPayment;
import org.stellar.anchor.api.shared.Refunds;
import org.stellar.anchor.util.TransactionsParams;

public interface Sep6TransactionStore {
  Sep6Transaction newInstance();

  Refunds newRefunds();

  RefundPayment newRefundPayment();

  Sep6Transaction findByTransactionId(String transactionId) throws SepException;

  Sep6Transaction findByStellarTransactionId(String stellarTransactionId) throws SepException;

  Sep6Transaction findByExternalTransactionId(String externalTransactionId) throws SepException;

  List<Sep6Transaction> findTransactions(
      String accountId, String accountMemo, GetTransactionsRequest request) throws SepException;

  Sep6Transaction save(Sep6Transaction sep6Transaction) throws SepException;

  List<? extends Sep6Transaction> findTransactions(TransactionsParams params) throws SepException;
}
