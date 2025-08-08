package org.stellar.anchor.platform.service;

import static org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.ERROR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.EXPIRED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_USR_TRANSFER_START;
import static org.stellar.anchor.event.EventService.EventQueue.TRANSACTION;
import static org.stellar.anchor.sep31.Sep31Helper.allAmountAvailable;
import static org.stellar.anchor.util.BeanHelper.updateField;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.equalsAsDecimals;
import static org.stellar.anchor.util.MemoHelper.makeMemo;
import static org.stellar.anchor.util.MetricConstants.*;

import com.google.common.collect.ImmutableSet;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InternalServerErrorException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.platform.GetTransactionsResponse;
import org.stellar.anchor.api.platform.PatchTransactionRequest;
import org.stellar.anchor.api.platform.PatchTransactionsRequest;
import org.stellar.anchor.api.platform.PatchTransactionsResponse;
import org.stellar.anchor.api.platform.PlatformTransactionData;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.TransactionsSeps;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.event.EventService.Session;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep31Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.utils.AssetValidationUtils;
import org.stellar.anchor.platform.utils.PlatformTransactionHelper;
import org.stellar.anchor.sep24.Sep24DepositInfoGenerator;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.sep6.Sep6DepositInfoGenerator;
import org.stellar.anchor.sep6.Sep6Transaction;
import org.stellar.anchor.sep6.Sep6TransactionStore;
import org.stellar.anchor.util.*;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.SepHelper;
import org.stellar.anchor.util.StringHelper;
import org.stellar.anchor.util.TransactionsParams;
import org.stellar.sdk.Memo;

public class TransactionService {

  private final Sep38QuoteStore quoteStore;
  private final Sep6TransactionStore txn6Store;
  private final Sep24TransactionStore txn24Store;
  private final Sep31TransactionStore txn31Store;

  private final List<AssetInfo> assets;
  private final Session eventSession;
  private final AssetService assetService;

  private final Sep6DepositInfoGenerator sep6DepositInfoGenerator;
  private final Sep24DepositInfoGenerator sep24DepositInfoGenerator;
  private final Counter findSep6TransactionCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTION, SEP, TV_SEP6);
  private final Counter findSep24TransactionCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTION, TYPE, TV_SEP24);
  private final Counter findSep31TransactionCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTION, TYPE, TV_SEP31);
  private final Counter findUnknownTransactionCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTION, TYPE, TV_UNKNOWN);

  private final Counter findSep6TransactionsCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTIONS, TYPE, TV_UNKNOWN);
  private final Counter findSep24TransactionsCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTIONS, TYPE, TV_UNKNOWN);
  private final Counter findSep31TransactionsCounter =
      Metrics.counter(PLATFORM_FIND_TRANSACTIONS, TYPE, TV_UNKNOWN);

  @SuppressWarnings("unused")
  private final Counter patchSep6TransactionCounter =
      Metrics.counter(PLATFORM_PATCH_TRANSACTION, SEP, TV_SEP6);

  private final Counter patchSep24TransactionCounter =
      Metrics.counter(PLATFORM_PATCH_TRANSACTION, SEP, TV_SEP24);
  private final Counter patchSep31TransactionCounter =
      Metrics.counter(PLATFORM_PATCH_TRANSACTION, SEP, TV_SEP31);

  static boolean isStatusError(String status) {
    return List.of(PENDING_CUSTOMER_INFO_UPDATE.getStatus(), EXPIRED.getStatus(), ERROR.getStatus())
        .contains(status);
  }

  public TransactionService(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      Sep38QuoteStore quoteStore,
      AssetService assetService,
      EventService eventService,
      Sep6DepositInfoGenerator sep6DepositInfoGenerator,
      Sep24DepositInfoGenerator sep24DepositInfoGenerator) {
    this.txn6Store = txn6Store;
    this.txn24Store = txn24Store;
    this.txn31Store = txn31Store;
    this.quoteStore = quoteStore;
    this.assets = assetService.getAssets();
    this.eventSession = eventService.createSession(this.getClass().getName(), TRANSACTION);
    this.assetService = assetService;
    this.sep6DepositInfoGenerator = sep6DepositInfoGenerator;
    this.sep24DepositInfoGenerator = sep24DepositInfoGenerator;
  }

  /**
   * Fetch the transaction and convert the transaction to an object of GetTransactionResponse class.
   *
   * @param txnId the transaction ID
   * @return the result
   */
  public GetTransactionResponse findTransaction(String txnId) throws AnchorException {
    if (Objects.toString(txnId, "").isEmpty()) {
      Log.info("Rejecting GET {platformApi}/transaction/:id because the id is empty.");
      throw new BadRequestException("transaction id cannot be empty");
    }
    JdbcSepTransaction txn = queryTransactionById(txnId);
    if (txn != null) {
      switch (txn.getProtocol()) {
        case "6":
          findSep6TransactionCounter.increment();
          break;
        case "24":
          findSep24TransactionCounter.increment();
          break;
        case "31":
          findSep31TransactionCounter.increment();
          break;
        default:
          findUnknownTransactionCounter.increment();
      }

      return PlatformTransactionHelper.toGetTransactionResponse(txn, assetService);
    } else {
      throw new NotFoundException(String.format("transaction (id=%s) is not found", txnId));
    }
  }

  public GetTransactionsResponse findTransactions(TransactionsSeps sep, TransactionsParams params)
      throws AnchorException {
    List<?> txn;

    switch (sep) {
      case SEP_6:
        txn = txn6Store.findTransactions(params);
        findSep6TransactionsCounter.increment();
        break;
      case SEP_24:
        txn = txn24Store.findTransactions(params);
        findSep24TransactionsCounter.increment();
        break;
      case SEP_31:
        txn = txn31Store.findTransactions(params);
        findSep31TransactionsCounter.increment();
        break;
      default:
        throw new BadRequestException("SEP not supported");
    }

    return new GetTransactionsResponse(
        txn.stream()
            .map(
                t ->
                    PlatformTransactionHelper.toGetTransactionResponse(
                        (JdbcSepTransaction) t, assetService))
            .collect(Collectors.toList()));
  }

  /**
   * Query transaction by id.
   *
   * @param txnId the transaction ID
   * @return an object of JdbcSepTransaction
   */
  JdbcSepTransaction queryTransactionById(String txnId) throws AnchorException {
    Sep31Transaction txn31 = txn31Store.findByTransactionId(txnId);
    if (txn31 != null) {
      return (JdbcSep31Transaction) txn31;
    }
    Sep6Transaction txn6 = txn6Store.findByTransactionId(txnId);
    if (txn6 != null) {
      return (JdbcSep6Transaction) txn6;
    }

    return (JdbcSep24Transaction) txn24Store.findByTransactionId(txnId);
  }

  /**
   * Patch transactions.
   *
   * @param request the request
   * @return the response
   */
  @Deprecated
  public PatchTransactionsResponse patchTransactions(PatchTransactionsRequest request)
      throws AnchorException {
    List<PatchTransactionRequest> patchRequests = request.getRecords();
    if (patchRequests == null) {
      throw new BadRequestException("Records are missing.");
    }

    List<GetTransactionResponse> txnResponses = new LinkedList<>();

    for (PatchTransactionRequest patchRequest : patchRequests) {
      txnResponses.add(patchTransaction(patchRequest));
    }

    return new PatchTransactionsResponse(txnResponses);
  }

  @Deprecated
  private GetTransactionResponse patchTransaction(PatchTransactionRequest patch)
      throws AnchorException {
    if (patch.getTransaction() == null) {
      throw new BadRequestException("Transaction is missing.");
    }

    validateIfStatusIsSupported(patch.getTransaction().getStatus().toString());
    validateAsset("amount_in", patch.getTransaction().getAmountIn());
    validateAsset("amount_out", patch.getTransaction().getAmountOut());

    FeeDetails feeDetails = patch.getTransaction().getFeeDetails();

    JdbcSepTransaction txn = queryTransactionById(patch.getTransaction().getId());
    if (txn == null)
      throw new BadRequestException(
          String.format("transaction(id=%s) not found", patch.getTransaction().getId()));

    updateSepTransaction(patch.getTransaction(), txn);
    switch (txn.getProtocol()) {
      case "6":
        JdbcSep6Transaction sep6Transaction = (JdbcSep6Transaction) txn;
        Log.infoF(
            "Updating SEP-6 transaction: {}", GsonUtils.getInstance().toJson(sep6Transaction));

        boolean shouldCreateWithdrawTxn =
            ImmutableSet.of(Kind.WITHDRAWAL, Kind.WITHDRAWAL_EXCHANGE)
                    .contains(Kind.from(sep6Transaction.getKind()))
                && txn.getStatus().equals(PENDING_USR_TRANSFER_START.toString());

        if (sep6Transaction.getMemo() == null && shouldCreateWithdrawTxn) {
          SepDepositInfo sep6DepositInfo = sep6DepositInfoGenerator.generate(sep6Transaction);
          sep6Transaction.setWithdrawAnchorAccount(sep6DepositInfo.getStellarAddress());
          sep6Transaction.setMemo(sep6DepositInfo.getMemo());
          sep6Transaction.setMemoType(sep6DepositInfo.getMemoType());
        }

        if (feeDetails != null) {
          sep6Transaction.setFeeDetails(feeDetails);
        }

        txn6Store.save(sep6Transaction);
        eventSession.publish(
            AnchorEvent.builder()
                .id(UUID.randomUUID().toString())
                .sep("6")
                .type(TRANSACTION_STATUS_CHANGED)
                .transaction(
                    TransactionMapper.toGetTransactionResponse(sep6Transaction, assetService))
                .build());
        patchSep6TransactionCounter.increment();
        break;
      case "24":
        JdbcSep24Transaction sep24Txn = (JdbcSep24Transaction) txn;

        if (feeDetails != null) {
          sep24Txn.setFeeDetails(feeDetails);
        }

        txn24Store.save(sep24Txn);
        eventSession.publish(
            AnchorEvent.builder()
                .id(UUID.randomUUID().toString())
                .sep("24")
                .type(TRANSACTION_STATUS_CHANGED)
                .transaction(TransactionMapper.toGetTransactionResponse(sep24Txn, assetService))
                .build());
        patchSep24TransactionCounter.increment();
        break;
      case "31":
        JdbcSep31Transaction sep31Txn = (JdbcSep31Transaction) txn;

        if (feeDetails != null) {
          sep31Txn.setFeeDetails(feeDetails);
        }

        txn31Store.save(sep31Txn);
        eventSession.publish(
            AnchorEvent.builder()
                .id(UUID.randomUUID().toString())
                .sep("31")
                .type(TRANSACTION_STATUS_CHANGED)
                .transaction(TransactionMapper.toGetTransactionResponse(sep31Txn))
                .build());
        patchSep31TransactionCounter.increment();
        break;
    }

    return PlatformTransactionHelper.toGetTransactionResponse(txn, assetService);
  }

  @Deprecated
  void updateSepTransaction(PlatformTransactionData patch, JdbcSepTransaction txn)
      throws AnchorException {
    boolean txnUpdated = false;
    boolean shouldClearMessageStatus =
        !StringHelper.isEmpty(patch.getStatus().getStatus())
            && !isStatusError(patch.getStatus().getStatus())
            && !StringHelper.isEmpty(txn.getStatus())
            && isStatusError(txn.getStatus());
    // update status
    txnUpdated = updateField(patch, "status.status", txn, "status", txnUpdated);
    // update amount_in
    txnUpdated = updateField(patch, "amountIn.amount", txn, "amountIn", txnUpdated);
    txnUpdated = updateField(patch, "amountIn.asset", txn, "amountInAsset", txnUpdated);
    // update amount_out
    txnUpdated = updateField(patch, "amountOut.amount", txn, "amountOut", txnUpdated);
    txnUpdated = updateField(patch, "amountOut.asset", txn, "amountOutAsset", txnUpdated);
    // update started_at, completed_at, updated_at, transferReceivedAt
    txnUpdated = updateField(patch, txn, "startedAt", txnUpdated);
    txnUpdated = updateField(patch, txn, "updatedAt", txnUpdated);
    txnUpdated = updateField(patch, txn, "completedAt", txnUpdated);
    txnUpdated = updateField(patch, txn, "transferReceivedAt", txnUpdated);
    txnUpdated = updateField(patch, txn, "userActionRequiredBy", txnUpdated);
    // update external_transaction_id
    txnUpdated = updateField(patch, txn, "externalTransactionId", txnUpdated);
    // update stellar_transactions
    txnUpdated = updateField(patch, txn, "stellarTransactions", txnUpdated);

    if (patch.getStellarTransactions() != null) {
      patch.getStellarTransactions().stream()
          .max(Comparator.comparingLong(x -> x.getCreatedAt().toEpochMilli()))
          .ifPresent(stellarTransaction -> txn.setStellarTransactionId(stellarTransaction.getId()));
    }

    FeeDetails feeDetails = patch.getFeeDetails();

    switch (txn.getProtocol()) {
      case "6":
        JdbcSep6Transaction sep6Txn = (JdbcSep6Transaction) txn;
        txnUpdated = updateField(patch, sep6Txn, "requiredInfoMessage", txnUpdated);
        txnUpdated = updateField(patch, sep6Txn, "requiredInfoUpdates", txnUpdated);
        txnUpdated = updateField(patch, sep6Txn, "instructions", txnUpdated);
        if (feeDetails != null) {
          sep6Txn.setFeeDetails(feeDetails);
        }
        break;
      case "24":
        JdbcSep24Transaction sep24Txn = (JdbcSep24Transaction) txn;

        Memo memo = makeMemo(patch.getMemo(), patch.getMemoType());

        if (memo != null) {
          txnUpdated = updateField(patch, sep24Txn, "memo", txnUpdated);
          txnUpdated = updateField(patch, sep24Txn, "memoType", txnUpdated);
        }

        // add a memo for the transaction if the transaction is ready for user to send funds
        if (sep24Txn.getMemo() == null
            && Kind.WITHDRAWAL.getKind().equals(sep24Txn.getKind())
            && sep24Txn.getStatus().equals(PENDING_USR_TRANSFER_START.toString())) {
          SepDepositInfo sep24DepositInfo = sep24DepositInfoGenerator.generate(sep24Txn);
          sep24Txn.setWithdrawAnchorAccount(sep24DepositInfo.getStellarAddress());
          sep24Txn.setMemo(sep24DepositInfo.getMemo());
          sep24Txn.setMemoType(sep24DepositInfo.getMemoType());
        }

        txnUpdated = updateField(patch, sep24Txn, "message", txnUpdated);
        txnUpdated = updateField(patch, sep24Txn, "withdrawAnchorAccount", txnUpdated);

        // update refunds
        if (patch.getRefunds() != null) {
          Sep24Refunds updatedRefunds = Sep24Refunds.of(patch.getRefunds(), txn24Store);
          if (!Objects.equals(sep24Txn.getRefunds(), updatedRefunds)) {
            sep24Txn.setRefunds(updatedRefunds);
            txnUpdated = true;
          }
        }
        if (feeDetails != null) {
          sep24Txn.setFeeDetails(feeDetails);
        }
        break;
      case "31":
        // update message
        if (shouldClearMessageStatus) {
          JdbcSep31Transaction sep31Txn = (JdbcSep31Transaction) txn;
          sep31Txn.setRequiredInfoMessage(null);
        } else {
          txnUpdated = updateField(patch, "message", txn, "requiredInfoMessage", txnUpdated);
        }

        JdbcSep31Transaction sep31Txn = (JdbcSep31Transaction) txn;
        // update sender and receiver
        txnUpdated = updateField(patch, "customers.sender", txn, "senderId", txnUpdated);
        txnUpdated = updateField(patch, "customers.receiver", txn, "receiverId", txnUpdated);
        // update creator
        txnUpdated = updateField(patch, txn, "creator", txnUpdated);
        // update refunds
        if (patch.getRefunds() != null) {
          Sep31Refunds updatedSep31Refunds = Sep31Refunds.of(patch.getRefunds(), txn31Store);
          if (!Objects.equals(sep31Txn.getRefunds(), updatedSep31Refunds)) {
            sep31Txn.setRefunds(updatedSep31Refunds);
            txnUpdated = true;
          }
        }
        if (feeDetails != null) {
          sep31Txn.setFeeDetails(feeDetails);
        }

        validateQuoteAndAmounts(sep31Txn);
        break;
    }

    Instant now = Instant.now();
    if (txnUpdated) {
      txn.setUpdatedAt(now);
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
    SepHelper.validateAmount(fieldName + ".", amount.getAmount(), false);

    // asset name cannot be empty
    if (StringHelper.isEmpty(amount.getAsset())) {
      throw new BadRequestException(fieldName + ".asset cannot be empty");
    }

    // asset name needs to be supported
    if (assets.stream().noneMatch(assetInfo -> assetInfo.getId().equals(amount.getAsset()))) {
      throw new BadRequestException(
          String.format("'%s' is not a supported asset.", amount.getAsset()));
    }

    List<AssetInfo> allAssets =
        assets.stream()
            .filter(assetInfo -> assetInfo.getId().equals(amount.getAsset()))
            .collect(Collectors.toList());

    AssetValidationUtils.valiateAssetDecimals(allAssets, amount.getAmount());
  }

  void validateQuoteAndAmounts(Sep31Transaction txn) throws AnchorException {
    // amount_in = amount_out + amount_fee
    if (StringHelper.isEmpty(txn.getQuoteId())) {
      // without exchange and not indicative
      if (allAmountAvailable(txn)
          && Objects.equals(txn.getAmountInAsset(), txn.getAmountOutAsset()))
        if (decimal(txn.getAmountIn())
                .compareTo(decimal(txn.getAmountOut()).add(decimal(txn.getFeeDetails().getTotal())))
            != 0) throw new BadRequestException("amount_in != amount_out + fee_details.total");
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

      if (!Objects.equals(txn.getFeeDetails().getAsset(), quote.getFee().getAsset())) {
        throw new BadRequestException("transaction.fee_details.asset != quote.fee.asset");
      }

      if (!equalsAsDecimals(txn.getFeeDetails().getTotal(), quote.getFee().getTotal())) {
        throw new BadRequestException("transaction.fee_details.total != quote.fee.total");
      }
    }
  }
}
