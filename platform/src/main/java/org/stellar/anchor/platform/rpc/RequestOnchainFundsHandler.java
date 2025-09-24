package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL_EXCHANGE;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_6;
import static org.stellar.anchor.api.rpc.method.RpcMethod.REQUEST_ONCHAIN_FUNDS;
import static org.stellar.anchor.api.sep.SepTransactionStatus.*;
import static org.stellar.anchor.util.MemoHelper.makeMemo;
import static org.stellar.anchor.util.MemoHelper.memoType;
import static org.stellar.anchor.util.SepHelper.memoTypeString;

import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.AmountAssetRequest;
import org.stellar.anchor.api.rpc.method.RequestOnchainFundsRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.service.Sep24DepositInfoNoneGenerator;
import org.stellar.anchor.platform.service.Sep6DepositInfoNoneGenerator;
import org.stellar.anchor.platform.utils.AssetValidationUtils;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator;
import org.stellar.anchor.sep6.Sep6TransactionStore;
import org.stellar.anchor.util.CustodyUtils;
import org.stellar.sdk.Memo;

public class RequestOnchainFundsHandler extends RpcMethodHandler<RequestOnchainFundsRequest> {

  private final CustodyService custodyService;
  private final CustodyConfig custodyConfig;
  private final Sep6DepositInfoGenerator sep6DepositInfoGenerator;
  private final Sep24DepositInfoGenerator sep24DepositInfoGenerator;

  public RequestOnchainFundsHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      CustodyService custodyService,
      CustodyConfig custodyConfig,
      Sep6DepositInfoGenerator sep6DepositInfoGenerator,
      Sep24DepositInfoGenerator sep24DepositInfoGenerator,
      EventService eventService,
      MetricsService metricsService) {
    super(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService,
        RequestOnchainFundsRequest.class);
    this.custodyService = custodyService;
    this.custodyConfig = custodyConfig;
    this.sep6DepositInfoGenerator = sep6DepositInfoGenerator;
    this.sep24DepositInfoGenerator = sep24DepositInfoGenerator;
  }

  @Override
  protected void validate(JdbcSepTransaction txn, RequestOnchainFundsRequest request)
      throws InvalidParamsException, InvalidRequestException, BadRequestException {
    super.validate(txn, request);

    // If none of the accepted combinations of input parameters satisfies -> throw an exception
    if (!((request.getAmountIn() == null
            && request.getAmountFee() == null
            && request.getFeeDetails() == null
            && request.getAmountExpected() == null)
        || (request.getAmountIn() != null
            && (request.getAmountFee() != null || request.getFeeDetails() != null)))) {
      throw new InvalidParamsException(
          "All (amount_out is optional) or none of the amount_in, amount_out, and (fee_details or amount_fee) should be set");
    }

    // In case 2nd predicate in previous IF statement was TRUE
    if (request.getFeeDetails() != null && request.getAmountFee() != null) {
      throw new InvalidParamsException("Either fee_details or amount_fee should be set");
    }

    if (request.getAmountIn() != null) {
      if (!AssetValidationUtils.isStellarAsset(request.getAmountIn().getAsset())) {
        throw new InvalidParamsException("amount_in.asset should be stellar asset");
      }
      AssetValidationUtils.validateAssetAmount(
          "amount_in", request.getAmountIn(), true, assetService);
    }
    if (request.getAmountOut() != null) {
      if (AssetValidationUtils.isStellarAsset(request.getAmountOut().getAsset())) {
        throw new InvalidParamsException("amount_out.asset should be non-stellar asset");
      }
      AssetValidationUtils.validateAssetAmount(
          "amount_out", request.getAmountOut(), true, assetService);
    }
    if (request.getAmountFee() != null) {
      if (!AssetValidationUtils.isStellarAsset(request.getAmountFee().getAsset())) {
        throw new InvalidParamsException("amount_fee.asset should be stellar asset");
      }
      AssetValidationUtils.validateAssetAmount(
          "amount_fee", request.getAmountFee(), true, assetService);
    }
    if (request.getFeeDetails() != null) {
      if (!AssetValidationUtils.isStellarAsset(request.getFeeDetails().getAsset())) {
        throw new InvalidParamsException("fee_details.asset should be stellar asset");
      }
      AssetValidationUtils.validateFeeDetails(request.getFeeDetails(), txn, assetService);
    }
    if (request.getAmountExpected() != null) {
      AssetValidationUtils.validateAssetAmount(
          "amount_expected",
          AmountAssetRequest.builder()
              .amount(request.getAmountExpected().getAmount())
              .asset(request.getAmountIn().getAsset())
              .build(),
          assetService);
    }

    if (request.getAmountIn() == null && txn.getAmountIn() == null) {
      throw new InvalidParamsException("amount_in is required");
    }
    if (request.getAmountOut() == null && txn.getAmountOut() == null) {
      if (SEP_6 == Sep.from(txn.getProtocol())) {
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (txn6.getQuoteId() != null) {
          throw new InvalidParamsException(
              "amount_out is required for transactions with firm quotes");
        }
        if (StringUtils.equals(txn6.getAmountInAsset(), txn6.getAmountOutAsset())) {
          throw new InvalidParamsException("amount_out is required for non-exchange transactions");
        }
      }
      if (SEP_24 == Sep.from(txn.getProtocol())) {
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (txn24.getQuoteId() != null) {
          throw new InvalidParamsException(
              "amount_out is required for transactions with firm quotes");
        }
        if (StringUtils.equals(txn24.getAmountInAsset(), txn24.getAmountOutAsset())) {
          throw new InvalidParamsException("amount_out is required for non-exchange transactions");
        }
      }
    }
    if (request.getAmountFee() == null
        && request.getFeeDetails() == null
        && txn.getAmountFee() == null) {
      throw new InvalidParamsException("fee_details or amount_fee is required");
    }

    boolean canGenerateSep6DepositInfo =
        SEP_6 == Sep.from(txn.getProtocol())
            && sep6DepositInfoGenerator instanceof Sep6DepositInfoNoneGenerator;
    boolean canGenerateSep24DepositInfo =
        SEP_24 == Sep.from(txn.getProtocol())
            && sep24DepositInfoGenerator instanceof Sep24DepositInfoNoneGenerator;
    if (canGenerateSep6DepositInfo || canGenerateSep24DepositInfo) {
      Memo memo;
      try {
        memo = makeMemo(request.getMemo(), request.getMemoType());
      } catch (SepException e) {
        throw new InvalidParamsException(
            String.format("Invalid memo or memo_type: %s", e.getMessage()), e);
      }

      if (memo == null) {
        throw new InvalidParamsException("memo and memo_type are required");
      }
      if (request.getDestinationAccount() == null) {
        throw new InvalidParamsException("destination_account is required");
      }
    } else if (request.getMemo() != null
        || request.getMemoType() != null
        || request.getDestinationAccount() != null) {
      throw new InvalidParamsException(
          "Anchor is not configured to accept memo, memo_type and destination_account. "
              + "Please set configuration sep24.deposit_info_generator_type to 'none' "
              + "if you want to enable this feature");
    }
  }

  @Override
  public RpcMethod getRpcMethod() {
    return REQUEST_ONCHAIN_FUNDS;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, RequestOnchainFundsRequest request) {
    return PENDING_USR_TRANSFER_START;
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    Set<SepTransactionStatus> supportedStatuses = new HashSet<>();
    if (SEP_6 == Sep.from(txn.getProtocol())) {
      JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
      if (ImmutableSet.of(WITHDRAWAL, WITHDRAWAL_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
        supportedStatuses.add(INCOMPLETE);
        supportedStatuses.add(PENDING_CUSTOMER_INFO_UPDATE);
        if (!areFundsReceived(txn6)) {
          supportedStatuses.add(PENDING_ANCHOR);
        }
      }
      return supportedStatuses;
    }
    if (SEP_24 == Sep.from(txn.getProtocol())) {
      JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
      if (WITHDRAWAL == Kind.from(txn24.getKind())) {
        supportedStatuses.add(INCOMPLETE);
        if (!areFundsReceived(txn24)) {
          supportedStatuses.add(PENDING_ANCHOR);
        }
      }
      return supportedStatuses;
    }
    return Collections.emptySet();
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, RequestOnchainFundsRequest request) throws AnchorException {
    if (request.getAmountIn() != null) {
      txn.setAmountIn(request.getAmountIn().getAmount());
      txn.setAmountInAsset(request.getAmountIn().getAsset());
    }
    if (request.getAmountOut() != null) {
      txn.setAmountOut(request.getAmountOut().getAmount());
      txn.setAmountOutAsset(request.getAmountOut().getAsset());
    }
    if (request.getAmountFee() != null) {
      txn.setAmountFee(request.getAmountFee().getAmount());
      txn.setAmountFeeAsset(request.getAmountFee().getAsset());
    }
    if (request.getFeeDetails() != null) {
      txn.setAmountFee(request.getFeeDetails().getTotal());
      txn.setAmountFeeAsset(request.getFeeDetails().getAsset());
      txn.setFeeDetailsList(request.getFeeDetails().getDetails());
    }

    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;

        if (request.getAmountExpected() != null) {
          txn6.setAmountExpected(request.getAmountExpected().getAmount());
        } else if (request.getAmountIn() != null) {
          txn6.setAmountExpected(request.getAmountIn().getAmount());
        }

        if (sep6DepositInfoGenerator instanceof Sep6DepositInfoNoneGenerator) {
          Memo memo = makeMemo(request.getMemo(), request.getMemoType());
          if (memo != null) {
            txn6.setMemo(request.getMemo());
            txn6.setMemoType(memoTypeString(memoType(memo)));
          }
          txn6.setWithdrawAnchorAccount(request.getDestinationAccount());
        } else {
          SepDepositInfo sep6DepositInfo = sep6DepositInfoGenerator.generate(txn6);
          txn6.setWithdrawAnchorAccount(sep6DepositInfo.getStellarAddress());
          txn6.setMemo(sep6DepositInfo.getMemo());
          txn6.setMemoType(sep6DepositInfo.getMemoType());
        }

        if (!CustodyUtils.isMemoTypeSupported(custodyConfig.getType(), txn6.getMemoType())) {
          throw new InvalidParamsException(
              String.format(
                  "Memo type[%s] is not supported for custody type[%s]",
                  txn6.getMemoType(), custodyConfig.getType()));
        }

        if (custodyConfig.isCustodyIntegrationEnabled()) {
          custodyService.createTransaction(txn6);
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;

        if (request.getAmountExpected() != null) {
          txn24.setAmountExpected(request.getAmountExpected().getAmount());
        } else if (request.getAmountIn() != null) {
          txn24.setAmountExpected(request.getAmountIn().getAmount());
        }

        if (sep24DepositInfoGenerator instanceof Sep24DepositInfoNoneGenerator) {
          Memo memo = makeMemo(request.getMemo(), request.getMemoType());
          if (memo != null) {
            txn24.setMemo(request.getMemo());
            txn24.setMemoType(memoTypeString(memoType(memo)));
          }
          txn24.setWithdrawAnchorAccount(request.getDestinationAccount());
          txn24.setToAccount(request.getDestinationAccount());
        } else {
          SepDepositInfo sep24DepositInfo = sep24DepositInfoGenerator.generate(txn24);
          txn24.setToAccount(sep24DepositInfo.getStellarAddress());
          txn24.setWithdrawAnchorAccount(sep24DepositInfo.getStellarAddress());
          txn24.setMemo(sep24DepositInfo.getMemo());
          txn24.setMemoType(sep24DepositInfo.getMemoType());
        }

        if (!CustodyUtils.isMemoTypeSupported(custodyConfig.getType(), txn24.getMemoType())) {
          throw new InvalidParamsException(
              String.format(
                  "Memo type[%s] is not supported for custody type[%s]",
                  txn24.getMemoType(), custodyConfig.getType()));
        }

        if (custodyConfig.isCustodyIntegrationEnabled()) {
          custodyService.createTransaction(txn24);
        }
        break;
      default:
        break;
    }
  }
}
