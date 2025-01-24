package org.stellar.anchor.ledger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import lombok.SneakyThrows;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse;
import org.stellar.sdk.xdr.AccountEntry;
import org.stellar.sdk.xdr.LedgerEntry;
import org.stellar.sdk.xdr.LedgerEntryType;
import org.stellar.sdk.xdr.LedgerKey;

public class StellarRpc implements LedgerApi {
  String rpcServerUrl;
  SorobanServer sorobanServer;

  public StellarRpc(String rpcServerUrl) {
    this.rpcServerUrl = rpcServerUrl;
    sorobanServer = new SorobanServer(rpcServerUrl);
  }

  @SneakyThrows
  @Override
  public boolean hasTrustline(String account, String asset) throws NetworkException {
    AccountEntry accountEntry = fetchAccountEntry(account);
    // TODO: Implement this method
    return false;
  }

  @Override
  public Account getAccount(String account) throws NetworkException {
    // TODO: Implement this method
    return null;
  }

  @Override
  public List<OperationResponse> getStellarTxnOperations(String stellarTxnId) {
    // TODO: Implement this method
    return List.of();
  }

  @Override
  public TransactionResponse submitTransaction(Transaction transaction) throws NetworkException {
    // TODO: Implement this method
    return null;
  }

  private AccountEntry fetchAccountEntry(String account) throws IOException {
    // TODO: Implement this method
    return null;
//    GetLedgerEntriesResponse response;
//    KeyPair keyPair = KeyPair.fromAccountId(account);
//
//    // Create ledger keys
//    List<LedgerKey> ledgerKeys =
//        Collections.singletonList(
//            LedgerKey.builder()
//                .account(
//                    LedgerKey.LedgerKeyAccount.builder()
//                        .accountID(keyPair.getXdrAccountId())
//                        .build())
//                .discriminant(LedgerEntryType.ACCOUNT)
//                .build());
//
//    // Get ledger entries
//    response = sorobanServer.getLedgerEntries(ledgerKeys);
//
//    // Print ledger entries
//    for (GetLedgerEntriesResponse.LedgerEntryResult result : response.getEntries()) {
//      LedgerEntry.LedgerEntryData ledgerEntryData =
//          LedgerEntry.LedgerEntryData.fromXdrBase64(result.getXdr());
//      if (ledgerEntryData.getDiscriminant() == LedgerEntryType.ACCOUNT) {
//        return ledgerEntryData.getAccount();
//      }
//    }
//    throw new NetworkException(404, "Account not found");
  }
}
