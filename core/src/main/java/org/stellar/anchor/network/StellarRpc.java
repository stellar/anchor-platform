package org.stellar.anchor.network;

import lombok.Getter;
import org.stellar.anchor.config.AppConfig;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilderAccount;
import org.stellar.sdk.responses.sorobanrpc.GetLatestLedgerResponse;
import org.stellar.sdk.responses.sorobanrpc.SimulateTransactionResponse;

@Getter
public class StellarRpc {
  private final SorobanServer rpc;

  public StellarRpc(AppConfig appConfig) {
    this.rpc = new SorobanServer(appConfig.getRpcUrl());
  }

  public TransactionBuilderAccount getAccount(String accountId) {
    return rpc.getAccount(accountId);
  }

  public SimulateTransactionResponse simulateTransaction(Transaction transaction) {
    return rpc.simulateTransaction(transaction);
  }

  public GetLatestLedgerResponse getLatestLedger() {
    return rpc.getLatestLedger();
  }
}
