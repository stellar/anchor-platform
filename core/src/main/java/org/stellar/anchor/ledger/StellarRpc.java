package org.stellar.anchor.ledger;

import static org.stellar.sdk.xdr.LedgerEntry.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.StrKey;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse;
import org.stellar.sdk.xdr.*;
import org.stellar.sdk.xdr.LedgerKey.LedgerKeyAccount;

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
  public Account getAccount(String account) throws NetworkException, IOException {
    AccountEntry ae = getAccountRpc(sorobanServer, account);
    org.stellar.sdk.xdr.Thresholds txdr = ae.getThresholds();
    org.stellar.sdk.xdr.Signer[] signersXdr = ae.getSigners();
    return Account.builder()
        .accountId(StrKey.encodeEd25519PublicKey(ae.getAccountID()))
        .sequenceNumber(ae.getSeqNum().getSequenceNumber().getInt64())
        .thresholds(
            new Thresholds(
                (int) txdr.getThresholds()[0],
                (int) txdr.getThresholds()[1],
                (int) txdr.getThresholds()[2],
                (int) txdr.getThresholds()[3]))
        .signers(
            Arrays.stream(signersXdr)
                .map(
                    s ->
                        Signer.builder()
                            .key(
                                StrKey.encodeEd25519PublicKey(s.getKey().getEd25519().getUint256()))
                            .type(s.getKey().getDiscriminant().name())
                            .weight(Math.toIntExact(s.getWeight().getUint32().getNumber()))
                            .build())
                .collect(Collectors.toList()))
        .build();
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

  private AccountEntry getAccountRpc(SorobanServer stellarRpc, String accountId)
      throws IOException {
    KeyPair kp = KeyPair.fromAccountId(accountId);

    LedgerKey ledgerKey =
        LedgerKey.builder()
            .account(LedgerKeyAccount.builder().accountID(kp.getXdrAccountId()).build())
            .discriminant(LedgerEntryType.ACCOUNT)
            .build();

    List<LedgerKey> ledgerKeys = Collections.singletonList(ledgerKey);
    GetLedgerEntriesResponse response = stellarRpc.getLedgerEntries(ledgerKeys);

    LedgerEntryData ledgerEntryData =
        LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr());
    return ledgerEntryData.getAccount();
  }
}
