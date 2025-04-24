package org.stellar.anchor.ledger;

import static java.lang.Thread.sleep;
import static org.stellar.sdk.xdr.LedgerEntry.*;
import static org.stellar.sdk.xdr.SignerKeyType.SIGNER_KEY_TYPE_ED25519;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerTransactionResponse;
import org.stellar.sdk.*;
import org.stellar.sdk.Asset;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TrustLineAsset;
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse.GetTransactionStatus;
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse;
import org.stellar.sdk.xdr.*;
import org.stellar.sdk.xdr.LedgerKey.LedgerKeyAccount;
import org.stellar.sdk.xdr.LedgerKey.LedgerKeyTrustLine;

/** The Stellar RPC server that implements LedgerClient. */
public class StellarRpc implements LedgerClient {
  String rpcServerUrl;
  SorobanServer sorobanServer;
  int maxTimeout = 10;
  int maxPollCount = 10;

  public StellarRpc(String rpcServerUrl) {
    this.rpcServerUrl = rpcServerUrl;
    sorobanServer = new SorobanServer(rpcServerUrl);
  }

  public StellarRpc(AppConfig appConfig) {
    this(appConfig.getRpcUrl());
  }

  @SneakyThrows
  @Override
  public boolean hasTrustline(String account, String asset) {
    return (getTrustlineRpc(account, asset) != null);
  }

  @Override
  public Account getAccount(String account) throws LedgerException {
    try {
      AccountEntry ae = getAccountRpc(account);
      org.stellar.sdk.xdr.Thresholds thresholdsXdr = ae.getThresholds();
      org.stellar.sdk.xdr.Signer[] signersXdr = ae.getSigners();
      List<Signer> signers =
          new ArrayList<>(
              Arrays.stream(signersXdr)
                  .map(
                      s ->
                          Signer.builder()
                              .key(
                                  StrKey.encodeEd25519PublicKey(
                                      s.getKey().getEd25519().getUint256()))
                              .type(s.getKey().getDiscriminant().name())
                              .weight(s.getWeight().getUint32().getNumber())
                              .build())
                  .toList());
      // Add master key which is not included in the signersXdr
      signers.add(
          Signer.builder()
              .key(account)
              .type(SIGNER_KEY_TYPE_ED25519.name())
              .weight((long) thresholdsXdr.getThresholds()[0])
              .build());

      return Account.builder()
          .accountId(StrKey.encodeEd25519PublicKey(ae.getAccountID()))
          .sequenceNumber(ae.getSeqNum().getSequenceNumber().getInt64())
          .thresholds(
              new Thresholds(
                  // master threshold txdr.getThresholds()[0] has no use in the context of the
                  // anchor
                  (int) thresholdsXdr.getThresholds()[1],
                  (int) thresholdsXdr.getThresholds()[2],
                  (int) thresholdsXdr.getThresholds()[3]))
          .signers(signers)
          .build();
    } catch (Exception e) {
      throw new LedgerException("Error getting account: " + account, e);
    }
  }

  @Override
  public LedgerTransaction getTransaction(String txnHash) throws LedgerException {
    GetTransactionResponse txn = sorobanServer.getTransaction(txnHash);
    if (txn == null
        || txn.getStatus() == GetTransactionStatus.NOT_FOUND
        || txn.getEnvelopeXdr() == null) {
      // not found
      return null;
    }

    if (txn.getStatus() == GetTransactionStatus.FAILED) {
      throw new LedgerException("Error getting transaction: " + txnHash);
    }

    TransactionEnvelope txnEnv;
    try {
      txnEnv = TransactionEnvelope.fromXdrBase64(txn.getEnvelopeXdr());
    } catch (IOException ioex) {
      throw new LedgerException("Unable to parse transaction envelope", ioex);
    }
    Integer applicationOrder = txn.getApplicationOrder();
    Long sequenceNumber = txn.getLedger();
    LedgerClientHelper.ParsedTransaction osm = LedgerClientHelper.parseTransaction(txnEnv, txnHash);

    return LedgerTransaction.builder()
        .hash(txn.getTxHash())
        .sourceAccount(osm.sourceAccount())
        .envelopeXdr(txn.getEnvelopeXdr())
        .memo(osm.memo())
        .sequenceNumber(sequenceNumber)
        .createdAt(Instant.ofEpochSecond(txn.getCreatedAt()))
        .operations(
            IntStream.range(0, osm.operations().length)
                .mapToObj(
                    opIndex ->
                        LedgerClientHelper.convert(
                            osm.sourceAccount(),
                            sequenceNumber,
                            applicationOrder,
                            opIndex + 1, // operation index is 1-based
                            osm.operations()[opIndex]))
                .filter(Objects::nonNull)
                .toList())
        .build();
  }

  @Override
  @SneakyThrows
  public LedgerTransactionResponse submitTransaction(Transaction transaction) {
    SendTransactionResponse txnR = sorobanServer.sendTransaction(transaction);

    return LedgerTransactionResponse.builder()
        .hash(txnR.getHash())
        .errorResultXdr(txnR.getErrorResultXdr())
        .status(txnR.getStatus())
        .build();
  }

  @SneakyThrows
  public static AccountEntry getAccountEntry(SorobanServer sorobanServer, String accountId) {
    KeyPair kp = KeyPair.fromAccountId(accountId);
    List<LedgerKey> ledgerKeys =
        Collections.singletonList(
            LedgerKey.builder()
                .account(LedgerKeyAccount.builder().accountID(kp.getXdrAccountId()).build())
                .discriminant(LedgerEntryType.ACCOUNT)
                .build());
    GetLedgerEntriesResponse response = sorobanServer.getLedgerEntries(ledgerKeys);
    return LedgerEntry.LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr())
        .getAccount();
  }

  void delay() throws InterruptedException {
    sleep(1000);
  }

  private TrustLineEntry getTrustlineRpc(String accountId, String asset) throws LedgerException {
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
    var response = sorobanServer.getLedgerEntries(ledgerKeys);

    if (response.getEntries().isEmpty()) return null;

    try {
      return LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr()).getTrustLine();
    } catch (IOException e) {
      throw new LedgerException("Error parsing trustline data", e);
    }
  }

  private AccountEntry getAccountRpc(String accountId) throws IOException {
    KeyPair kp = KeyPair.fromAccountId(accountId);

    LedgerKey ledgerKey =
        LedgerKey.builder()
            .account(LedgerKeyAccount.builder().accountID(kp.getXdrAccountId()).build())
            .discriminant(LedgerEntryType.ACCOUNT)
            .build();

    List<LedgerKey> ledgerKeys = Collections.singletonList(ledgerKey);
    GetLedgerEntriesResponse response = sorobanServer.getLedgerEntries(ledgerKeys);

    LedgerEntryData ledgerEntryData =
        LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr());
    return ledgerEntryData.getAccount();
  }
}
