package org.stellar.anchor.ledger;

import static org.stellar.anchor.api.asset.AssetInfo.NATIVE_ASSET_CODE;
import static org.stellar.anchor.ledger.LedgerClientHelper.*;
import static org.stellar.anchor.util.Log.debug;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.ledger.LedgerClientHelper.ParseResult;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerTransactionResponse;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.sdk.*;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TrustLineAsset;
import org.stellar.sdk.exception.BadRequestException;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.responses.AccountResponse;
import org.stellar.sdk.responses.SubmitTransactionAsyncResponse;
import org.stellar.sdk.responses.TransactionResponse;
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
    TransactionResponse txnResponse;
    try {
      txnResponse = getServer().transactions().transaction(txnHash);
    } catch (BadRequestException brex) {
      debug("Transaction not found: " + txnHash);
      return null;
    } catch (NetworkException nex) {
      throw new LedgerException("Error getting transaction: " + txnHash, nex);
    }

    TransactionEnvelope txnEnv;
    try {
      txnEnv = TransactionEnvelope.fromXdrBase64(txnResponse.getEnvelopeXdr());
    } catch (IOException ioex) {
      throw new LedgerException("Unable to parse transaction envelope", ioex);
    }

    // The relationship between TOID and application order is defined at:
    // https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0035.md
    int applicationOrder =
        TOID.fromInt64(Long.parseLong(txnResponse.getPagingToken())).getTransactionOrder();
    Long sequenceNumber = txnResponse.getLedger();

    ParseResult result = parseOperationAndSourceAccountAndMemo(txnEnv, txnHash);
    if (result == null) return null;
    List<LedgerOperation> operations =
        LedgerClientHelper.getLedgerOperations(applicationOrder, sequenceNumber, result);

    return LedgerTransaction.builder()
        .hash(txnResponse.getHash())
        .applicationOrder(applicationOrder)
        .ledger(txnResponse.getLedger())
        .sourceAccount(txnResponse.getSourceAccount())
        .envelopeXdr(txnResponse.getEnvelopeXdr())
        .memo(result.memo())
        .sequenceNumber(txnResponse.getSourceAccountSequence())
        .createdAt(Instant.parse(txnResponse.getCreatedAt()))
        .operations(operations)
        .build();
  }

  @Override
  public LedgerTransactionResponse submitTransaction(Transaction transaction) {
    SubmitTransactionAsyncResponse txnR = getServer().submitTransactionAsync(transaction, false);

    return LedgerTransactionResponse.builder()
        .hash(txnR.getHash())
        .errorResultXdr(txnR.getErrorResultXdr())
        .status(LedgerClientHelper.convert(txnR.getTxStatus()))
        .build();
  }
}
