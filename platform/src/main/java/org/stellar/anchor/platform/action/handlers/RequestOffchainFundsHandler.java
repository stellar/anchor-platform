package org.stellar.anchor.platform.action.handlers;

import static org.stellar.anchor.api.sep.SepTransactionStatus.INCOMPLETE;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_RECEIVER;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_SENDER;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_USR_TRANSFER_START;
import static org.stellar.anchor.platform.action.dto.ActionMethod.REQUEST_OFFCHAIN_FUNDS;

import java.util.HashSet;
import java.util.Set;
import javax.validation.Validator;
import org.springframework.stereotype.Service;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.platform.action.dto.ActionMethod;
import org.stellar.anchor.platform.action.dto.RequestOffchainFundsRequest;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;

@Service
public class RequestOffchainFundsHandler extends ActionHandler<RequestOffchainFundsRequest> {

  public RequestOffchainFundsHandler(
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Validator validator,
      AssetService assetService) {
    super(txn24Store, txn31Store, validator, assetService);
  }

  @Override
  public ActionMethod getActionType() {
    return REQUEST_OFFCHAIN_FUNDS;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, RequestOffchainFundsRequest request) {
    switch (txn.getProtocol()) {
      case "24":
        return PENDING_USR_TRANSFER_START;
      case "31":
        return PENDING_SENDER;
      default:
        throw new IllegalArgumentException(
            String.format(
                "Invalid protocol[%s] for action[%s]", txn.getProtocol(), getActionType()));
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    Set<SepTransactionStatus> supportedStatuses = new HashSet<>();
    supportedStatuses.add(INCOMPLETE);

    if (txn.getTransferReceivedAt() == null) {
      switch (txn.getProtocol()) {
        case "24":
          JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
          switch (Kind.from(txn24.getKind())) {
            case DEPOSIT:
              supportedStatuses.add(PENDING_ANCHOR);
          }
          break;
        case "31":
          if (txn.getTransferReceivedAt() == null) {
            supportedStatuses.add(PENDING_RECEIVER);
          }
          break;
      }
    }

    return supportedStatuses;
  }

  @Override
  protected Set<String> getSupportedProtocols() {
    return Set.of("24", "31");
  }

  @Override
  protected boolean isMessageRequired() {
    return false;
  }

  @Override
  protected void updateActionTransactionInfo(
      JdbcSepTransaction txn, RequestOffchainFundsRequest request) throws BadRequestException {
    validateAsset("amount_in", request.getAmountIn());
    validateAsset("amount_out", request.getAmountOut());
    validateAsset("amount_fee", request.getAmountFee(), true);
    validateAsset("amount_expected", request.getAmountOut());

    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;

    if (txn24.getAmountIn() == null) {
      if (request.getAmountIn() != null) {
        txn24.setAmountIn(request.getAmountIn().getAmount());
        txn24.setAmountInAsset(request.getAmountIn().getAsset());
      } else if (txn24.getAmountIn() == null) {
        throw new BadRequestException("amount_in is required");
      }
    }

    if (txn24.getAmountOut() == null) {
      if (request.getAmountOut() != null) {
        txn24.setAmountIn(request.getAmountOut().getAmount());
        txn24.setAmountInAsset(request.getAmountOut().getAsset());
      } else if (txn24.getAmountOut() == null) {
        throw new BadRequestException("amount_out is required");
      }
    }

    if (txn24.getAmountFee() == null) {
      if (request.getAmountFee() != null) {
        txn24.setAmountIn(request.getAmountFee().getAmount());
        txn24.setAmountInAsset(request.getAmountFee().getAsset());
      } else if (txn24.getAmountFee() == null) {
        throw new BadRequestException("amount_fee is required");
      }
    }

    if (txn24.getAmountExpected() == null) {
      if (request.getAmountExpected() != null) {
        txn24.setAmountExpected(request.getAmountExpected().getAmount());
      } else if (txn24.getAmountFee() == null) {
        txn24.setAmountExpected(txn.getAmountIn());
      }
    }
  }
}
