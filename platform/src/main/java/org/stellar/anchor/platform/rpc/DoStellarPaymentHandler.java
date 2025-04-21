package org.stellar.anchor.platform.rpc;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.DO_STELLAR_PAYMENT;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_STELLAR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_TRUST;

import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.Set;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.DoStellarPaymentRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;
import org.stellar.sdk.exception.NetworkException;

public class DoStellarPaymentHandler extends RpcTransactionStatusHandler<DoStellarPaymentRequest> {

  private final CustodyService custodyService;
  private final CustodyConfig custodyConfig;
  private final JdbcTransactionPendingTrustRepo transactionPendingTrustRepo;
  private final Horizon horizon;

  public DoStellarPaymentHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      CustodyConfig custodyConfig,
      Horizon horizon,
      AssetService assetService,
      CustodyService custodyService,
      EventService eventService,
      MetricsService metricsService,
      JdbcTransactionPendingTrustRepo transactionPendingTrustRepo) {
    super(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService,
        DoStellarPaymentRequest.class);
    this.custodyService = custodyService;
    this.custodyConfig = custodyConfig;
    this.horizon = horizon;
    this.transactionPendingTrustRepo = transactionPendingTrustRepo;
  }

  @Override
  protected void validate(JdbcSepTransaction txn, DoStellarPaymentRequest request)
      throws InvalidRequestException, InvalidParamsException, BadRequestException {
    super.validate(txn, request);

    if (!custodyConfig.isCustodyIntegrationEnabled()) {
      throw new InvalidRequestException(
          String.format("RPC method[%s] requires enabled custody integration", getRpcMethod()));
    }
  }

  @Override
  public RpcMethod getRpcMethod() {
    return DO_STELLAR_PAYMENT;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, DoStellarPaymentRequest request) {

    boolean trustlineConfigured = false;
    try {
      switch (Sep.from(txn.getProtocol())) {
        case SEP_6:
          JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
          trustlineConfigured =
              ledgerClient.hasTrustline(txn6.getToAccount(), txn6.getAmountOutAsset());
          break;
        case SEP_24:
          JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
          trustlineConfigured =
              ledgerClient.hasTrustline(txn24.getToAccount(), txn24.getAmountOutAsset());
          break;
        default:
          break;
      }
    } catch (LedgerException ex) {
      // assume trustline is not configured
    }

    if (trustlineConfigured) {
      return PENDING_STELLAR;
    } else {
      return PENDING_TRUST;
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
          if (areFundsReceived(txn6)) {
            return Set.of(PENDING_ANCHOR);
          }
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (DEPOSIT == Kind.from(txn24.getKind())) {
          if (areFundsReceived(txn24)) {
            return Set.of(PENDING_ANCHOR);
          }
        }
        break;
      default:
        break;
    }
    return emptySet();
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, DoStellarPaymentRequest request) throws AnchorException {
    boolean trustlineConfigured;
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;

        try {
          trustlineConfigured =
              ledgerClient.hasTrustline(txn6.getToAccount(), txn6.getAmountOutAsset());
        } catch (NetworkException ex) {
          trustlineConfigured = false;
        }

        if (trustlineConfigured) {
          custodyService.createTransactionPayment(txn6.getId(), null);
        } else {
          transactionPendingTrustRepo.save(
              JdbcTransactionPendingTrust.builder()
                  .id(txn6.getId())
                  .createdAt(Instant.now())
                  .asset(txn6.getAmountOutAsset())
                  .account(txn6.getToAccount())
                  .build());
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;

        try {
          trustlineConfigured =
              ledgerClient.hasTrustline(txn24.getToAccount(), txn24.getAmountOutAsset());
        } catch (NetworkException ex) {
          trustlineConfigured = false;
        }

        if (trustlineConfigured) {
          custodyService.createTransactionPayment(txn24.getId(), null);
        } else {
          transactionPendingTrustRepo.save(
              JdbcTransactionPendingTrust.builder()
                  .id(txn24.getId())
                  .createdAt(Instant.now())
                  .asset(txn24.getAmountOutAsset())
                  .account(txn24.getToAccount())
                  .build());
        }
        break;
      default:
        break;
    }
  }
}
