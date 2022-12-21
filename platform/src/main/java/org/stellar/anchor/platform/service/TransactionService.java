package org.stellar.anchor.platform.service;

import static org.stellar.anchor.api.sep.SepTransactionStatus.*;
import static org.stellar.anchor.sep31.Sep31Helper.allAmountAvailable;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.equalsAsDecimals;

import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InternalServerErrorException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.platform.PatchTransactionRequest;
import org.stellar.anchor.api.platform.PatchTransactionsRequest;
import org.stellar.anchor.api.platform.PatchTransactionsResponse;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.Refund;
import org.stellar.anchor.api.shared.RefundPayment;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.models.TransactionEvent;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.sep24.Sep24RefundPayment;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.SepHelper;
import org.stellar.anchor.util.StringHelper;

@Service
public class TransactionService {
  private final Sep38QuoteStore quoteStore;
  private final Sep31TransactionStore txn31Store;
  private final Sep24TransactionStore txn24Store;
  private final List<AssetInfo> assets;

  static boolean isStatusError(String status) {
    return List.of(PENDING_CUSTOMER_INFO_UPDATE.getName(), EXPIRED.getName(), ERROR.getName())
        .contains(status);
  }

  TransactionService(
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Sep38QuoteStore quoteStore,
      AssetService assetService) {
    this.txn24Store = txn24Store;
    this.txn31Store = txn31Store;
    this.quoteStore = quoteStore;
    this.assets = assetService.listAllAssets();
  }

  public GetTransactionResponse getTransaction(String txnId) throws AnchorException {
    if (Objects.toString(txnId, "").isEmpty()) {
      Log.info("Rejecting GET {platformApi}/transaction/:id because the id is empty.");
      throw new BadRequestException("transaction id cannot be empty");
    }

    Sep31Transaction txn31 = txn31Store.findByTransactionId(txnId);
    if (txn31 != null) {
      return toGetTransactionResponse(txn31);
    }

    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn24Store.findByTransactionId(txnId);
    if (txn24 != null) {
      return toGetTransactionResponse(txn24);
    }

    throw new NotFoundException(String.format("transaction (id=%s) is not found", txnId));
  }

  public PatchTransactionsResponse patchTransactions(PatchTransactionsRequest request)
      throws AnchorException {
    List<PatchTransactionRequest> patchRequests = request.getRecords();
    List<String> ids =
        patchRequests.stream().map(PatchTransactionRequest::getId).collect(Collectors.toList());
    List<? extends Sep31Transaction> fetchedTxns = txn31Store.findByTransactionIds(ids);
    Map<String, ? extends Sep31Transaction> sep31Transactions =
        fetchedTxns.stream()
            .collect(Collectors.toMap(Sep31Transaction::getId, Function.identity()));

    List<Sep31Transaction> txnsToSave = new LinkedList<>();
    List<GetTransactionResponse> responses = new LinkedList<>();
    List<Sep31Transaction> statusUpdatedTxns = new LinkedList<>();

    for (PatchTransactionRequest patch : patchRequests) {
      Sep31Transaction txn = sep31Transactions.get(patch.getId());
      if (txn != null) {
        String txnOriginalStatus = txn.getStatus();
        // validate and update the transaction.
        updateSep31Transaction(patch, txn);
        // Add them to the to-be-updated lists.
        txnsToSave.add(txn);
        if (!txnOriginalStatus.equals(txn.getStatus())) {
          statusUpdatedTxns.add(txn);
        }
        responses.add(toGetTransactionResponse(txn));
      } else {
        throw new BadRequestException(String.format("transaction(id=%s) not found", patch.getId()));
      }
    }
    for (Sep31Transaction txn : txnsToSave) {
      // TODO: consider 2-phase commit DB transaction management.
      txn31Store.save(txn);
    }
    for (Sep31Transaction txn : statusUpdatedTxns) {
      Metrics.counter(AnchorMetrics.SEP31_TRANSACTION.toString(), "status", txn.getStatus())
          .increment();
    }
    return new PatchTransactionsResponse(responses);
  }

  /**
   * updateSep31Transaction will inject the new values from the PatchTransactionRequest into the
   * database Sep31Transaction and validate them to make sure they are compliant.
   *
   * @param ptr is the PatchTransactionRequest containing the updated values.
   * @param txn is the Sep31Transaction stored in the database that needs to be updated.
   * @throws AnchorException if any of the new values is invalid or non-compliant.
   */
  void updateSep31Transaction(PatchTransactionRequest ptr, Sep31Transaction txn)
      throws AnchorException {
    boolean txWasUpdated = false;
    boolean txWasCompleted = false;
    boolean shouldClearMessageStatus =
        !StringHelper.isEmpty(ptr.getStatus())
            && !isStatusError(ptr.getStatus())
            && !StringHelper.isEmpty(txn.getStatus())
            && isStatusError(txn.getStatus());

    if (ptr.getStatus() != null && !Objects.equals(txn.getStatus(), ptr.getStatus())) {
      validateIfStatusIsSupported(ptr.getStatus());
      txWasCompleted =
          !Objects.equals(txn.getStatus(), COMPLETED.getName())
              && Objects.equals(ptr.getStatus(), COMPLETED.getName());
      txn.setStatus(ptr.getStatus());
      txWasUpdated = true;
    }

    if (ptr.getAmountIn() != null
        && (!Objects.equals(txn.getAmountIn(), ptr.getAmountIn().getAmount())
            || !Objects.equals(txn.getAmountInAsset(), ptr.getAmountIn().getAsset()))) {
      validateAsset("amount_in", ptr.getAmountIn());
      txn.setAmountIn(ptr.getAmountIn().getAmount());
      txn.setAmountInAsset(ptr.getAmountIn().getAsset());
      txWasUpdated = true;
    }

    if (ptr.getAmountOut() != null
        && (!Objects.equals(txn.getAmountOut(), ptr.getAmountOut().getAmount())
            || !Objects.equals(txn.getAmountOutAsset(), ptr.getAmountOut().getAsset()))) {
      validateAsset("amount_out", ptr.getAmountOut());
      txn.setAmountOut(ptr.getAmountOut().getAmount());
      txn.setAmountOutAsset(ptr.getAmountOut().getAsset());
      txWasUpdated = true;
    }

    if (ptr.getAmountFee() != null
        && (!Objects.equals(txn.getAmountFee(), ptr.getAmountFee().getAmount())
            || !Objects.equals(txn.getAmountFeeAsset(), ptr.getAmountFee().getAsset()))) {
      validateAsset("amount_fee", ptr.getAmountFee());
      txn.setAmountFee(ptr.getAmountFee().getAmount());
      txn.setAmountFeeAsset(ptr.getAmountFee().getAsset());
      txWasUpdated = true;
    }

    if (ptr.getTransferReceivedAt() != null
        && ptr.getTransferReceivedAt().compareTo(txn.getStartedAt()) != 0) {
      if (ptr.getTransferReceivedAt().compareTo(txn.getStartedAt()) < 0) {
        throw new BadRequestException(
            String.format(
                "the `transfer_received_at(%s)` cannot be earlier than 'started_at(%s)'",
                ptr.getTransferReceivedAt().toString(), txn.getStartedAt().toString()));
      }
      txn.setTransferReceivedAt(ptr.getTransferReceivedAt());
      txWasUpdated = true;
    }

    if (ptr.getMessage() != null) {
      if (!Objects.equals(txn.getRequiredInfoMessage(), ptr.getMessage())) {
        txn.setRequiredInfoMessage(ptr.getMessage());
        txWasUpdated = true;
      }
    } else if (shouldClearMessageStatus) {
      txn.setRequiredInfoMessage(null);
    }

    if (ptr.getRefunds() != null) {
      Refunds updatedRefunds = Refunds.of(ptr.getRefunds(), txn31Store);
      // TODO: validate refunds
      if (!Objects.equals(txn.getRefunds(), updatedRefunds)) {
        txn.setRefunds(updatedRefunds);
        txWasUpdated = true;
      }
    }

    if (ptr.getExternalTransactionId() != null
        && !Objects.equals(txn.getExternalTransactionId(), ptr.getExternalTransactionId())) {
      txn.setExternalTransactionId(ptr.getExternalTransactionId());
      txWasUpdated = true;
    }

    validateQuoteAndAmounts(txn);

    Instant now = Instant.now();
    if (txWasUpdated) {
      txn.setUpdatedAt(now);
    }
    if (txWasCompleted) {
      txn.setCompletedAt(now);
    }
  }

  /**
   * validateIfStatusIsSupported will check if the provided string is a SepTransactionStatus
   * supported by the PlatformAPI
   *
   * @param status a String representing the SepTransactionStatus
   * @throws BadRequestException if the provided status is not supported
   */
  void validateIfStatusIsSupported(String status) throws BadRequestException {
    if (!SepTransactionStatus.isValid(status)) {
      throw new BadRequestException(String.format("invalid status(%s)", status));
    }
  }

  /**
   * validateAsset will validate if the provided amount has valid values and if its asset is
   * supported.
   *
   * @param amount is the object containing the asset full name and the amount.
   * @throws BadRequestException if the provided asset is not supported
   */
  void validateAsset(String fieldName, Amount amount) throws BadRequestException {
    if (amount == null) {
      return;
    }

    // asset amount needs to be non-empty and valid
    SepHelper.validateAmount(fieldName + ".", amount.getAmount());

    // asset name cannot be empty
    if (StringHelper.isEmpty(amount.getAsset())) {
      throw new BadRequestException(fieldName + ".asset cannot be empty");
    }

    // asset name needs to be supported
    if (assets.stream()
        .noneMatch(assetInfo -> assetInfo.getAssetName().equals(amount.getAsset()))) {
      throw new BadRequestException(
          String.format("'%s' is not a supported asset.", amount.getAsset()));
    }
  }

  void validateQuoteAndAmounts(Sep31Transaction txn) throws AnchorException {
    // amount_in = amount_out + amount_fee
    if (StringHelper.isEmpty(txn.getQuoteId())) {
      // without exchange
      if (allAmountAvailable(txn))
        if (decimal(txn.getAmountIn())
                .compareTo(decimal(txn.getAmountOut()).add(decimal(txn.getAmountFee())))
            != 0) throw new BadRequestException("amount_in != amount_out + amount_fee");
    } else {
      // with exchange
      Sep38Quote quote = quoteStore.findByQuoteId(txn.getQuoteId());
      if (quote == null) {
        throw new InternalServerErrorException(
            String.format(
                "invalid quote_id(id=%s) found in transaction(id=%s)",
                txn.getQuoteId(), txn.getId()));
      }

      if (!Objects.equals(txn.getAmountInAsset(), quote.getSellAsset())) {
        throw new BadRequestException("transaction.amount_in_asset != quote.sell_asset");
      }

      if (!equalsAsDecimals(txn.getAmountIn(), quote.getSellAmount())) {
        throw new BadRequestException("transaction.amount_in != quote.sell_amount");
      }

      if (!Objects.equals(txn.getAmountOutAsset(), quote.getBuyAsset())) {
        throw new BadRequestException("transaction.amount_out_asset != quote.buy_asset");
      }

      if (!equalsAsDecimals(txn.getAmountOut(), quote.getBuyAmount())) {
        throw new BadRequestException("transaction.amount_out != quote.buy_amount");
      }

      if (!Objects.equals(txn.getAmountFeeAsset(), quote.getFee().getAsset())) {
        throw new BadRequestException("transaction.amount_fee_asset != quote.fee.asset");
      }

      if (!equalsAsDecimals(txn.getAmountFee(), quote.getFee().getTotal())) {
        throw new BadRequestException("amount_fee != sum(quote.fee.total)");
      }
    }
  }

  GetTransactionResponse toGetTransactionResponse(Sep31Transaction txn) {
    Refund refunds = null;
    if (txn.getRefunds() != null) {
      refunds = txn.getRefunds().toPlatformApiRefund(txn.getAmountInAsset());
    }

    return org.stellar.anchor.api.platform.GetTransactionResponse.builder()
        .id(txn.getId())
        .sep(31)
        .kind(TransactionEvent.Kind.RECEIVE.getKind())
        .status(txn.getStatus())
        .amountExpected(new Amount(txn.getAmountExpected(), txn.getAmountInAsset()))
        .amountIn(new Amount(txn.getAmountIn(), txn.getAmountInAsset()))
        .amountOut(new Amount(txn.getAmountOut(), txn.getAmountOutAsset()))
        .amountFee(new Amount(txn.getAmountFee(), txn.getAmountFeeAsset()))
        .quoteId(txn.getQuoteId())
        .startedAt(txn.getStartedAt())
        .updatedAt(txn.getUpdatedAt())
        .completedAt(txn.getCompletedAt())
        .transferReceivedAt(txn.getTransferReceivedAt())
        .message(txn.getRequiredInfoMessage()) // Assuming these are meant to be the same.
        .refunds(refunds)
        .stellarTransactions(txn.getStellarTransactions())
        .externalTransactionId(txn.getExternalTransactionId())
        .customers(txn.getCustomers())
        .creator(txn.getCreator())
        .build();
  }

  RefundPayment toRefundPayment(Sep24RefundPayment refundPayment, String assetName) {
    return org.stellar.anchor.api.shared.RefundPayment.builder()
        .id(refundPayment.getId())
        .idType(org.stellar.anchor.api.shared.RefundPayment.IdType.STELLAR)
        .amount(new Amount(refundPayment.getAmount(), assetName))
        .fee(new Amount(refundPayment.getFee(), assetName))
        .requestedAt(null)
        .refundedAt(null)
        .build();
  }

  org.stellar.anchor.api.shared.Refund toRefunds(Sep24Refunds refunds, String assetName) {
    // build payments
    org.stellar.anchor.api.shared.RefundPayment[] payments =
        refunds.getRefundPayments().stream()
            .map(refundPayment -> toRefundPayment(refundPayment, assetName))
            .toArray(org.stellar.anchor.api.shared.RefundPayment[]::new);

    return org.stellar.anchor.api.shared.Refund.builder()
        .amountRefunded(new Amount(refunds.getAmountRefunded(), assetName))
        .amountFee(new Amount(refunds.getAmountFee(), assetName))
        .payments(payments)
        .build();
  }

  GetTransactionResponse toGetTransactionResponse(JdbcSep24Transaction txn) {
    Refund refunds = null;
    if (txn.getRefunds() != null) {
      refunds = toRefunds(txn.getRefunds(), txn.getAmountInAsset());
    }

    return org.stellar.anchor.api.platform.GetTransactionResponse.builder()
        .id(txn.getId())
        .sep(24)
        .kind(txn.getKind())
        .status(txn.getStatus())
        .amountIn(new Amount(txn.getAmountIn(), txn.getAmountInAsset()))
        .amountOut(new Amount(txn.getAmountOut(), txn.getAmountOutAsset()))
        .amountFee(new Amount(txn.getAmountFee(), txn.getAmountFeeAsset()))
        .startedAt(txn.getStartedAt())
        .updatedAt(txn.getUpdatedAt())
        .completedAt(txn.getCompletedAt())
        .refunds(refunds)
        .stellarTransactions(txn.getStellarTransactions())
        .externalTransactionId(txn.getExternalTransactionId())
        .build();
  }
}
