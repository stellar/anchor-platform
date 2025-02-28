package org.stellar.anchor.ledger;

import lombok.SneakyThrows;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.exception.NetworkException;

public class StellarRpc implements LedgerClient {
  String rpcServerUrl;
  SorobanServer sorobanServer;

  public StellarRpc(String rpcServerUrl) {
    this.rpcServerUrl = rpcServerUrl;
    sorobanServer = new SorobanServer(rpcServerUrl);
  }

  @SneakyThrows
  @Override
  public boolean hasTrustline(String account, String asset) throws NetworkException {
    // TODO: Implement this method
    return false;
  }

  @Override
  public Account getAccount(String account) throws NetworkException {
    // TODO: Implement this method
    return null;
  }

  @Override
  public LedgerTransaction getTransaction(String stellarTxnId) {
    return null;
  }

  @Override
  public LedgerTransaction.LedgerTransactionResponse submitTransaction(Transaction transaction)
      throws NetworkException {
    // TODO: Implement this method
    return null;
  }
}
