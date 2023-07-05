package org.stellar.anchor.platform.action;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL;
import static org.stellar.anchor.api.rpc.action.ActionMethod.NOTIFY_ONCHAIN_FUNDS_RECEIVED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_USR_TRANSFER_START;

import java.util.Set;
import javax.validation.Validator;
import org.springframework.stereotype.Service;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.rpc.action.ActionMethod;
import org.stellar.anchor.api.rpc.action.AmountRequest;
import org.stellar.anchor.api.rpc.action.NotifyOnchainFundsReceivedRequest;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.horizon.Horizon;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;

@Service
public class NotifyOnchainFundsReceivedHandler
    extends ActionHandler<NotifyOnchainFundsReceivedRequest> {

  public NotifyOnchainFundsReceivedHandler(
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Validator validator,
      Horizon horizon,
      AssetService assetService) {
    super(txn24Store, txn31Store, validator, horizon, assetService);
  }

  @Override
  public ActionMethod getActionType() {
    return NOTIFY_ONCHAIN_FUNDS_RECEIVED;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyOnchainFundsReceivedRequest request)
      throws InvalidRequestException {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (WITHDRAWAL == Kind.from(txn24.getKind())) {
      return PENDING_ANCHOR;
    }
    throw new InvalidRequestException(
        String.format(
            "Invalid kind[%s] for protocol[%s] and action[%s]",
            txn24.getKind(), txn24.getProtocol(), getActionType()));
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
    if (WITHDRAWAL == Kind.from(txn24.getKind())) {
      return Set.of(PENDING_USR_TRANSFER_START);
    }
    return Set.of();
  }

  @Override
  protected Set<String> getSupportedProtocols() {
    return Set.of("24");
  }

  @Override
  protected void updateTransactionWithAction(
      JdbcSepTransaction txn, NotifyOnchainFundsReceivedRequest request) throws AnchorException {

    if (request.getStellarTransactionId() == null) {
      throw new InvalidParamsException("stellar_transaction_id is required");
    }

    if (!((request.getAmountIn() == null
            && request.getAmountOut() == null
            && request.getAmountFee() == null)
        || (request.getAmountIn() != null
            && request.getAmountOut() != null
            && request.getAmountFee() != null)
        || (request.getAmountIn() != null
            && request.getAmountOut() == null
            && request.getAmountFee() == null))) {
      throw new InvalidParamsException(
          "Invalid amounts combination provided: all, none or only amount_in should be set");
    }

    addStellarTransaction(txn, request.getStellarTransactionId());

    if (request.getAmountIn() != null) {
      validateAsset(
          "amount_in",
          AmountRequest.builder()
              .amount(request.getAmountIn())
              .asset(txn.getAmountInAsset())
              .build());
      txn.setAmountIn(request.getAmountIn());
    }
    if (request.getAmountOut() != null) {
      validateAsset(
          "amount_out",
          AmountRequest.builder()
              .amount(request.getAmountOut())
              .asset(txn.getAmountOutAsset())
              .build());
      txn.setAmountOut(request.getAmountOut());
    }
    if (request.getAmountFee() != null) {
      validateAsset(
          "amount_fee",
          AmountRequest.builder()
              .amount(request.getAmountFee())
              .asset(txn.getAmountFeeAsset())
              .build(),
          true);
      txn.setAmountFee(request.getAmountFee());
    }

    txn.setStellarTransactionId(request.getStellarTransactionId());
  }
}
