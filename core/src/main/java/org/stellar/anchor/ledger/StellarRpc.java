package org.stellar.anchor.ledger;

import static java.lang.Thread.sleep;
import static org.stellar.anchor.util.Log.info;
import static org.stellar.sdk.xdr.LedgerEntry.*;
import static org.stellar.sdk.xdr.OperationType.PAYMENT;
import static org.stellar.sdk.xdr.SignerKeyType.SIGNER_KEY_TYPE_ED25519;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
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
import org.stellar.sdk.xdr.Memo;

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
    return (getTrustlineRpc(sorobanServer, account, asset) != null);
  }

  @Override
  public Account getAccount(String account) throws LedgerException {
    try {
      AccountEntry ae = getAccountRpc(sorobanServer, account);
      org.stellar.sdk.xdr.Thresholds txdr = ae.getThresholds();
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
      // Add master key
      signers.add(
          Signer.builder()
              .key(account)
              .type(SIGNER_KEY_TYPE_ED25519.name())
              .weight((long) txdr.getThresholds()[0])
              .build());

      return Account.builder()
          .accountId(StrKey.encodeEd25519PublicKey(ae.getAccountID()))
          .sequenceNumber(ae.getSeqNum().getSequenceNumber().getInt64())
          .thresholds(
              new Thresholds(
                  // master threshold txdr.getThresholds()[0] has no use in the context of the
                  // anchor
                  (int) txdr.getThresholds()[1],
                  (int) txdr.getThresholds()[2],
                  (int) txdr.getThresholds()[3]))
          .signers(signers)
          .build();
    } catch (Exception e) {
      throw new LedgerException("Error getting account: " + account, e);
    }
  }

  @Override
  @SneakyThrows
  public LedgerTransaction getTransaction(String txnHash) {
    GetTransactionResponse txn = sorobanServer.getTransaction(txnHash);
    if (txn == null
        || txn.getStatus() != GetTransactionStatus.SUCCESS
        || txn.getEnvelopeXdr() == null) {
      // not found or not yet available or failure
      return null;
    }
    TransactionEnvelope txnEnv = TransactionEnvelope.fromXdrBase64(txn.getEnvelopeXdr());
    Integer applicationOrder = txn.getApplicationOrder();
    Operation[] operations;
    Memo memo;
    Long sequenceNumber = txn.getLedger();
    String sourceAccount;

    if (txnEnv.getV0() != null) {
      TransactionV0Envelope tenv = txnEnv.getV0();
      operations = tenv.getTx().getOperations();
      sourceAccount =
          StrKey.encodeEd25519PublicKey(tenv.getTx().getSourceAccountEd25519().getUint256());
      memo = tenv.getTx().getMemo();
    } else if (txnEnv.getV1() != null) {
      TransactionV1Envelope tenv = txnEnv.getV1();
      operations = tenv.getTx().getOperations();
      sourceAccount =
          StrKey.encodeEd25519PublicKey(tenv.getTx().getSourceAccount().getEd25519().getUint256());
      memo = tenv.getTx().getMemo();
    } else {
      return null;
    }

    return LedgerTransaction.builder()
        .hash(txn.getTxHash())
        .applicationOrder(txn.getApplicationOrder())
        .sourceAccount(sourceAccount)
        .envelopeXdr(txn.getEnvelopeXdr())
        .memo(memo)
        .sequenceNumber(sequenceNumber)
        .createdAt(Instant.ofEpochSecond(txn.getCreatedAt()))
        .operations(
            IntStream.range(0, operations.length)
                .mapToObj(
                    opIndex ->
                        from(
                            sourceAccount,
                            sequenceNumber,
                            applicationOrder,
                            opIndex + 1, // operation index is 1-based
                            operations[opIndex]))
                .filter(Objects::nonNull)
                .toList())
        .build();
  }

  LedgerOperation from(
      String sourceAccount,
      Long sequenceNumber,
      Integer applicationOrder,
      int opIndex,
      Operation op) {
    if (op == null) {
      return null;
    }
    if (op.getBody() == null) {
      return null;
    }
    String operationId =
        String.valueOf(new TOID(sequenceNumber.intValue(), applicationOrder, opIndex).toInt64());
    PaymentOp payment = op.getBody().getPaymentOp();
    return switch (op.getBody().getDiscriminant()) {
      case PAYMENT ->
          LedgerOperation.builder()
              .type(PAYMENT)
              .paymentOperation(
                  LedgerPaymentOperation.builder()
                      .id(operationId)
                      .asset(payment.getAsset())
                      .amount(payment.getAmount().getInt64())
                      .from(sourceAccount)
                      .to(
                          StrKey.encodeEd25519PublicKey(
                              payment.getDestination().getEd25519().getUint256()))
                      .build())
              .build();
      case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND ->
          LedgerOperation.builder()
              .type(
                  switch (op.getBody().getDiscriminant()) {
                    case PATH_PAYMENT_STRICT_RECEIVE -> OperationType.PATH_PAYMENT_STRICT_RECEIVE;
                    case PATH_PAYMENT_STRICT_SEND -> OperationType.PATH_PAYMENT_STRICT_SEND;
                    default -> null;
                  })
              .pathPaymentOperation(
                  LedgerPathPaymentOperation.builder()
                      .id(operationId)
                      .asset(payment.getAsset())
                      .amount(payment.getAmount().getInt64())
                      .from(sourceAccount)
                      .to(
                          StrKey.encodeEd25519PublicKey(
                              payment.getDestination().getEd25519().getUint256()))
                      .build())
              .build();
      default -> null;
    };
  }

  @Override
  @SneakyThrows
  public LedgerTransactionResponse submitTransaction(Transaction transaction) {
    SendTransactionResponse txnR = sorobanServer.sendTransaction(transaction);
    LedgerTransaction txn = null;
    Instant startTime = Instant.now();
    int pollCount = 0;
    try {
      do {
        delay();
        if (java.time.Duration.between(startTime, Instant.now()).getSeconds() > maxTimeout
            || pollCount >= maxPollCount)
          throw new InterruptedException("Transaction took too long to complete");
        txn = getTransaction(txnR.getHash());
        pollCount++;
      } while (txn == null);
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

  void delay() throws InterruptedException {
    sleep(1000);
  }

  private TrustLineEntry getTrustlineRpc(SorobanServer stellarRpc, String accountId, String asset)
      throws LedgerException {
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

    if (response.getEntries().isEmpty()) return null;

    try {
      return LedgerEntryData.fromXdrBase64(response.getEntries().get(0).getXdr()).getTrustLine();
    } catch (IOException e) {
      throw new LedgerException("Error parsing trustline data", e);
    }
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
