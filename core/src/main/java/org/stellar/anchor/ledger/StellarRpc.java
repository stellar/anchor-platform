package org.stellar.anchor.ledger;

import static java.lang.Thread.sleep;
import static org.stellar.anchor.util.Log.info;
import static org.stellar.sdk.xdr.LedgerEntry.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerTransactionResponse;
import org.stellar.sdk.Asset;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.SorobanServer;
import org.stellar.sdk.StrKey;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TrustLineAsset;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse;
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse;
import org.stellar.sdk.xdr.*;
import org.stellar.sdk.xdr.LedgerKey.LedgerKeyAccount;
import org.stellar.sdk.xdr.LedgerKey.LedgerKeyTrustLine;

public class StellarRpc implements LedgerClient {
  String rpcServerUrl;
  SorobanServer sorobanServer;
  long maxTxnWait = 15;

  public StellarRpc(String rpcServerUrl) {
    this.rpcServerUrl = rpcServerUrl;
    sorobanServer = new SorobanServer(rpcServerUrl);
  }

  @SneakyThrows
  @Override
  public boolean hasTrustline(String account, String asset) throws NetworkException {
    return (getTrustlineRpc(sorobanServer, account, asset) != null);
  }

  @Override
  @SneakyThrows
  public Account getAccount(String account) throws NetworkException {
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
                            .weight(s.getWeight().getUint32().getNumber())
                            .build())
                .collect(Collectors.toList()))
        .build();
  }

  @Override
  @SneakyThrows
  public LedgerTransaction getTransaction(String txnHash) {
    GetTransactionResponse txn = sorobanServer.getTransaction(txnHash);
    TransactionEnvelope txnEnv = TransactionEnvelope.fromXdrBase64(txn.getEnvelopeXdr());
    if (txnEnv.getV0() != null) {
      TransactionV0Envelope tenv = txnEnv.getV0();
      return LedgerTransaction.builder()
          .hash(txn.getTxHash())
          .sourceAccount(
              StrKey.encodeEd25519PublicKey(tenv.getTx().getSourceAccountEd25519().getUint256()))
          .envelopeXdr(txn.getEnvelopeXdr())
          .memo(org.stellar.sdk.Memo.fromXdr(tenv.getTx().getMemo()))
          .sequenceNumber(tenv.getTx().getSeqNum().getSequenceNumber().getInt64())
          .createdAt(Instant.ofEpochSecond(txn.getCreatedAt()))
          .build();

    } else if (txnEnv.getV1() != null) {
      TransactionV1Envelope tenv = txnEnv.getV1();
      return LedgerTransaction.builder()
          .hash(txn.getTxHash())
          .sourceAccount(
              StrKey.encodeEd25519PublicKey(
                  tenv.getTx().getSourceAccount().getEd25519().getUint256()))
          .envelopeXdr(txn.getEnvelopeXdr())
          .memo(org.stellar.sdk.Memo.fromXdr(tenv.getTx().getMemo()))
          .sequenceNumber(tenv.getTx().getSeqNum().getSequenceNumber().getInt64())
          .createdAt(Instant.ofEpochSecond(txn.getCreatedAt()))
          .build();
    }

    // not found
    return null;
  }

  @Override
  @SneakyThrows
  public LedgerTransactionResponse submitTransaction(Transaction transaction)
      throws NetworkException {
    SendTransactionResponse txnR = sorobanServer.sendTransaction(transaction);
    LedgerTransaction txn = null;
    Instant startTime = Instant.now();
    try {
      do {
        //noinspection BusyWait
        sleep(1000);
        if (java.time.Duration.between((Instant.now()), startTime).getSeconds() > maxTxnWait) {
          throw new InterruptedException("Transaction took too long to complete");
        }
      } while ((txn = getTransaction(txnR.getHash())) == null);
    } catch (InterruptedException e) {
      info("Interrupted while waiting for transaction to complete");
    }

    if (txn != null) {
      return LedgerTransactionResponse.builder()
          .hash(txnR.getHash())
          .envelopXdr(txn.getEnvelopeXdr())
          .sourceAccount(txn.getSourceAccount())
          .createdAt(txn.getCreatedAt())
          .build();
    } else {
      return null;
    }
  }

  private TrustLineEntry getTrustlineRpc(SorobanServer stellarRpc, String accountId, String asset)
      throws IOException {
    KeyPair kp = KeyPair.fromAccountId(accountId);

    // Create ledger keys for querying account and trustline
    List<LedgerKey> ledgerKeys = new ArrayList<>();
    ledgerKeys.add(
        LedgerKey.builder()
            .trustLine(
                LedgerKeyTrustLine.builder()
                    .accountID(kp.getXdrAccountId())
                    .asset(new TrustLineAsset(Asset.create(asset)).toXdr())
                    .build())
            .discriminant(LedgerEntryType.TRUSTLINE)
            .build());

    // Assuming `stellarRpc` is defined elsewhere
    var response = stellarRpc.getLedgerEntries(ledgerKeys);

    return LedgerEntry.LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr())
        .getTrustLine();
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
