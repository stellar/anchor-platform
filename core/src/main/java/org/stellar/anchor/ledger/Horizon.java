package org.stellar.anchor.ledger;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;
import static org.stellar.sdk.xdr.OperationType.PAYMENT;
import static org.stellar.sdk.xdr.SignerKeyType.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerTransactionResponse;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.*;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.TransactionResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;
import org.stellar.sdk.xdr.AssetType;
import org.stellar.sdk.xdr.OperationType;
import org.stellar.sdk.xdr.SignerKeyType;

/** The horizon-server. */
public class Horizon implements LedgerClient {
  @Getter private final String horizonUrl;
  @Getter private final String stellarNetworkPassphrase;
  private final Server horizonServer;

  public Horizon(String horizonUrl, String stellarNetworkPassphrase) {
    this.horizonUrl = horizonUrl;
    this.stellarNetworkPassphrase = stellarNetworkPassphrase;
    this.horizonServer = new Server(horizonUrl);
  }

  public Horizon(AppConfig appConfig) {
    this(appConfig.getHorizonUrl(), appConfig.getStellarNetworkPassphrase());
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
      return getAccountInternal(account);
    } catch (Exception e) {
      throw new LedgerException("Error getting account: " + account, e);
    }
  }

  Account getAccountInternal(String account) {
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
  }

  @Override
  public LedgerTransaction getTransaction(String txnHash) {
    TransactionResponse response = getServer().transactions().transaction(txnHash);

    return LedgerTransaction.builder()
        .hash(response.getHash())
        // The page token is the TOID of a transaction
        .applicationOrder(
            TOID.fromInt64(Long.parseLong(response.getPagingToken())).getTransactionOrder())
        .sourceAccount(response.getSourceAccount())
        .envelopeXdr(response.getEnvelopeXdr())
        .memo(MemoHelper.toXdr(response.getMemo()))
        .sequenceNumber(response.getSourceAccountSequence())
        .createdAt(Instant.parse(response.getCreatedAt()))
        .build();
  }

  @Override
  public LedgerTransactionResponse submitTransaction(Transaction transaction) {
    TransactionResponse txnR = getServer().submitTransaction(transaction, false);

    return LedgerTransactionResponse.builder()
        .hash(txnR.getHash())
        .envelopXdr(txnR.getEnvelopeXdr())
        .sourceAccount(txnR.getSourceAccount())
        .feeCharged(txnR.getFeeCharged().toString())
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
              .from(paymentOp.getFrom())
              .to(paymentOp.getTo())
              .amount(Long.parseLong(paymentOp.getAmount()))
              .asset(paymentOp.getAsset().toXdr())
              .build());
    } else if (op instanceof PathPaymentBaseOperationResponse pathPaymentOp) {
      builder.type(OperationType.PATH_PAYMENT_STRICT_RECEIVE);
      builder.pathPaymentOperation(
          LedgerTransaction.LedgerPathPaymentOperation.builder()
              .sourceAccount(pathPaymentOp.getSourceAccount())
              .sourceAmount(pathPaymentOp.getSourceAmount())
              .sourceAsset(pathPaymentOp.getSourceAsset().toXdr())
              .from(pathPaymentOp.getFrom())
              .to(pathPaymentOp.getTo())
              .amount(Long.parseLong(pathPaymentOp.getAmount()))
              .asset(pathPaymentOp.getAsset().toXdr())
              .build());
    } else {
      throw new IllegalArgumentException("Unsupported operation type: " + op.getType());
    }
    return builder.build();
  }

  /**
   * Convert from Horizon signer key type to XDR signer key type.
   *
   * @param type the Horizon signer key type
   * @return the XDR signer key type
   */
  public SignerKeyType getKeyTypeDiscriminant(String type) {
    return switch (type) {
      case "ed25519_public_key" -> SIGNER_KEY_TYPE_ED25519;
      case "preauth_tx" -> SIGNER_KEY_TYPE_PRE_AUTH_TX;
      case "sha256_hash" -> SIGNER_KEY_TYPE_HASH_X;
      case "ed25519_signed_payload" -> SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD;
      default -> throw new IllegalArgumentException("Invalid signer key type: " + type);
    };
  }
}
