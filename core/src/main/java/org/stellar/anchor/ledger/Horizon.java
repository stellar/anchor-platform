package org.stellar.anchor.ledger;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;
import static org.stellar.anchor.util.AssetHelper.toXdrAmount;
import static org.stellar.sdk.xdr.OperationType.PAYMENT;
import static org.stellar.sdk.xdr.SignerKeyType.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.ledger.LedgerClientHelper.ParsedTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerTransactionResponse;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.*;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TrustLineAsset;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;
import org.stellar.sdk.xdr.*;

/** The horizon-server that implements LedgerClient. */
public class Horizon implements LedgerClient {
  private final Server horizonServer;

  public Horizon(String horizonUrl) {
    this.horizonServer = new Server(horizonUrl);
  }

  public Horizon(AppConfig appConfig) {
    this(appConfig.getHorizonUrl());
  }

  public Server getServer() {
    return this.horizonServer;
  }

  @Override
  public boolean hasTrustline(String account, String asset) {
    String assetCode = AssetHelper.getAssetCode(asset);
    if (NATIVE_ASSET_CODE.equals(assetCode)) {
      return true;
    }
    String assetIssuer = AssetHelper.getAssetIssuer(asset);

    AccountResponse accountResponse = getServer().accounts().account(account);
    return accountResponse.getBalances().stream()
        .anyMatch(
            balance -> {
              TrustLineAsset trustLineAsset = balance.getTrustLineAsset();
              if (trustLineAsset.getAssetType() == AssetType.ASSET_TYPE_CREDIT_ALPHANUM4
                  || trustLineAsset.getAssetType() == AssetType.ASSET_TYPE_CREDIT_ALPHANUM12) {
                AssetTypeCreditAlphaNum creditAsset =
                    (AssetTypeCreditAlphaNum) trustLineAsset.getAsset();
                assert creditAsset != null;
                return creditAsset.getCode().equals(assetCode)
                    && creditAsset.getIssuer().equals(assetIssuer);
              }
              return false;
            });
  }

  @Override
  public Account getAccount(String account) throws LedgerException {
    try {
      AccountResponse response = getServer().accounts().account(account);
      AccountResponse.Thresholds thresholds = response.getThresholds();

      return Account.builder()
          .accountId(response.getAccountId())
          .sequenceNumber(response.getSequenceNumber())
          .thresholds(
              LedgerClient.Thresholds.builder()
                  .low(thresholds.getLowThreshold())
                  .medium(thresholds.getMedThreshold())
                  .high(thresholds.getHighThreshold())
                  .build())
          .signers(
              response.getSigners().stream()
                  .map(
                      s ->
                          Signer.builder()
                              .key(s.getKey())
                              .type(getKeyTypeDiscriminant(s.getType()).name())
                              .weight((long) s.getWeight())
                              .build())
                  .collect(Collectors.toList()))
          .build();
    } catch (Exception e) {
      throw new LedgerException("Error getting account: " + account, e);
    }
  }

  @Override
  public LedgerTransaction getTransaction(String txnHash) throws LedgerException {
    TransactionResponse txnResponse = getServer().transactions().transaction(txnHash);
    TransactionEnvelope txnEnv;
    try {
      txnEnv = TransactionEnvelope.fromXdrBase64(txnResponse.getEnvelopeXdr());
    } catch (IOException ioex) {
      throw new LedgerException("Unable to parse transaction envelope", ioex);
    }

    int applicationOrder =
        TOID.fromInt64(Long.parseLong(txnResponse.getPagingToken())).getTransactionOrder();
    Long sequenceNumber = txnResponse.getLedger();

    ParsedTransaction osm = LedgerClientHelper.parseTransaction(txnEnv, txnHash);

    return LedgerTransaction.builder()
        .hash(txnResponse.getHash())
        .sourceAccount(txnResponse.getSourceAccount())
        .envelopeXdr(txnResponse.getEnvelopeXdr())
        .memo(osm.memo)
        .sequenceNumber(txnResponse.getSourceAccountSequence())
        .createdAt(Instant.parse(txnResponse.getCreatedAt()))
        .operations(
            IntStream.range(0, osm.operations.length)
                .mapToObj(
                    opIndex ->
                        LedgerClientHelper.convert(
                            osm.sourceAccount,
                            sequenceNumber,
                            applicationOrder,
                            opIndex + 1, // operation index is 1-based
                            osm.operations[opIndex]))
                .filter(Objects::nonNull)
                .toList())
        .build();
  }

  @Override
  public LedgerTransactionResponse submitTransaction(Transaction transaction) {
    TransactionResponse txnR = getServer().submitTransaction(transaction, false);

    return LedgerTransactionResponse.builder()
        .hash(txnR.getHash())
        .envelopXdr(txnR.getEnvelopeXdr())
        .sourceAccount(txnR.getSourceAccount())
        .feeCharged(Long.parseLong(txnR.getFeeCharged().toString()))
        .createdAt(Instant.parse(txnR.getCreatedAt()))
        .build();
  }

  public static LedgerTransaction toLedgerTransaction(
      Server server, TransactionResponse txnResponse) {
    return LedgerTransaction.builder()
        .hash(txnResponse.getHash())
        .sourceAccount(txnResponse.getSourceAccount())
        .envelopeXdr(txnResponse.getEnvelopeXdr())
        .memo(MemoHelper.toXdr(txnResponse.getMemo()))
        .sequenceNumber(txnResponse.getSourceAccountSequence())
        .createdAt(Instant.parse(txnResponse.getCreatedAt()))
        .operations(getLedgerOperations(server, txnResponse.getHash()))
        .build();
  }

  /**
   * Get the ledger operations for a given transaction.
   *
   * @param txnHash the transaction hash
   * @return the ledger operations
   */
  public static List<LedgerOperation> getLedgerOperations(Server server, String txnHash) {
    return server
        .payments()
        .includeTransactions(true)
        .forTransaction(txnHash)
        .execute()
        .getRecords()
        .stream()
        .map(Horizon::toLedgerOperation)
        .collect(Collectors.toList());
  }

  public static LedgerOperation toLedgerOperation(OperationResponse op) {
    LedgerOperation.LedgerOperationBuilder builder = LedgerOperation.builder();
    if (op instanceof PaymentOperationResponse paymentOp) {
      builder.type(PAYMENT);
      builder.paymentOperation(
          LedgerPaymentOperation.builder()
              .id(String.valueOf(paymentOp.getId()))
              .from(paymentOp.getFrom())
              .to(paymentOp.getTo())
              .amount(toXdrAmount(paymentOp.getAmount()))
              .asset(paymentOp.getAsset().toXdr())
              .build());
    } else if (op instanceof PathPaymentBaseOperationResponse pathPaymentOp) {
      builder.type(OperationType.PATH_PAYMENT_STRICT_RECEIVE);
      builder.pathPaymentOperation(
          LedgerTransaction.LedgerPathPaymentOperation.builder()
              .id(String.valueOf(pathPaymentOp.getId()))
              .from(pathPaymentOp.getFrom())
              .to(pathPaymentOp.getTo())
              .amount(toXdrAmount(pathPaymentOp.getAmount()))
              .asset(pathPaymentOp.getAsset().toXdr())
              .sourceAccount(pathPaymentOp.getSourceAccount())
              .sourceAmount(Long.parseLong(pathPaymentOp.getSourceAmount()))
              .sourceAsset(pathPaymentOp.getSourceAsset().toXdr())
              .build());
    } else {
      return null;
    }
    return builder.build();
  }

  /**
   * Convert from Horizon signer key type to XDR signer key type.
   *
   * @param type the Horizon signer key type
   * @return the XDR signer key type
   */
  SignerKeyType getKeyTypeDiscriminant(String type) {
    return switch (type) {
      case "ed25519_public_key" -> SIGNER_KEY_TYPE_ED25519;
      case "preauth_tx" -> SIGNER_KEY_TYPE_PRE_AUTH_TX;
      case "sha256_hash" -> SIGNER_KEY_TYPE_HASH_X;
      case "ed25519_signed_payload" -> SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD;
      default -> throw new IllegalArgumentException("Invalid signer key type: " + type);
    };
  }
}
