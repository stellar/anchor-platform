package org.stellar.anchor.platform.action;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24;
import static org.stellar.anchor.api.rpc.action.ActionMethod.DO_STELLAR_PAYMENT;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_STELLAR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_TRUST;

import java.util.Set;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.action.ActionMethod;
import org.stellar.anchor.api.rpc.action.DoStellarPaymentRequest;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.horizon.Horizon;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;

public class DoStellarPaymentHandler extends ActionHandler<DoStellarPaymentRequest> {

  private final CustodyService custodyService;
  private final CustodyConfig custodyConfig;
  private final Horizon horizon;

  public DoStellarPaymentHandler(
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      CustodyConfig custodyConfig,
      Horizon horizon,
      AssetService assetService,
      CustodyService custodyService,
      EventService eventService) {
    super(
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        DoStellarPaymentRequest.class);
    this.custodyService = custodyService;
    this.custodyConfig = custodyConfig;
    this.horizon = horizon;
  }

  @Override
  protected void validate(JdbcSepTransaction txn, DoStellarPaymentRequest request)
      throws InvalidRequestException, InvalidParamsException, BadRequestException {
    super.validate(txn, request);

    if (!custodyConfig.isCustodyIntegrationEnabled()) {
      throw new InvalidRequestException(
          String.format("Action[%s] requires disabled custody integration", getActionType()));
    }
  }

  @Override
  public ActionMethod getActionType() {
    return DO_STELLAR_PAYMENT;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, DoStellarPaymentRequest request) {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (horizon.isTrustLineConfigured(txn24.getToAccount(), txn24.getAmountOutAsset())) {
      return PENDING_STELLAR;
    } else {
      return PENDING_TRUST;
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    if (SEP_24 == Sep.from(txn.getProtocol())) {
      JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
      if (DEPOSIT == Kind.from(txn24.getKind())) {
        if (txn24.getTransferReceivedAt() != null) {
          return Set.of(PENDING_ANCHOR);
        }
      }
    }
    return emptySet();
  }

  @Override
  protected void updateTransactionWithAction(
      JdbcSepTransaction txn, DoStellarPaymentRequest request) throws AnchorException {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (horizon.isTrustLineConfigured(txn24.getToAccount(), txn24.getAmountOutAsset())) {
      custodyService.createTransactionPayment(txn24.getId(), null);
    }
  }
}