package org.stellar.anchor.sep6;

import static io.micrometer.core.instrument.Metrics.counter;
import static org.stellar.anchor.util.AssetHelper.isDepositEnabled;
import static org.stellar.anchor.util.AssetHelper.isWithdrawEnabled;
import static org.stellar.anchor.util.MemoHelper.makeMemo;
import static org.stellar.anchor.util.MemoHelper.memoType;
import static org.stellar.anchor.util.SepLanguageHelper.validateLanguage;

import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import java.util.*;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.sep.sep6.*;
import org.stellar.anchor.api.sep.sep6.InfoResponse.*;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.client.ClientFinder;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.Sep38Config;
import org.stellar.anchor.config.Sep6Config;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.sep6.ExchangeAmountsCalculator.Amounts;
import org.stellar.anchor.util.MetricConstants;
import org.stellar.anchor.util.SepHelper;
import org.stellar.anchor.util.TransactionMapper;
import org.stellar.sdk.Memo;

public class Sep6Service {
  private final AppConfig appConfig;
  private final Sep6Config sep6Config;
  private final Sep38Config sep38Config;
  private final AssetService assetService;
  private final RequestValidator requestValidator;
  private final ClientFinder clientFinder;
  private final Sep6TransactionStore txnStore;
  private final ExchangeAmountsCalculator exchangeAmountsCalculator;
  private final EventService.Session eventSession;
  private final InfoResponse infoResponse;
  private final MoreInfoUrlConstructor moreInfoUrlConstructor;
  private final Counter sep6TransactionRequestedCounter =
      counter(MetricConstants.SEP6_TRANSACTION_REQUESTED);
  private final Counter sep6TransactionQueriedCounter =
      counter(MetricConstants.SEP6_TRANSACTION_QUERIED);
  private final Counter sep6WithdrawalCounter =
      counter(
          MetricConstants.SEP6_TRANSACTION_CREATED,
          MetricConstants.TYPE,
          MetricConstants.TV_SEP6_WITHDRAWAL);
  private final Counter sep6WithdrawalExchangeCounter =
      counter(
          MetricConstants.SEP6_TRANSACTION_CREATED,
          MetricConstants.TYPE,
          MetricConstants.TV_SEP6_WITHDRAWAL_EXCHANGE);
  private final Counter sep6DepositCounter =
      counter(
          MetricConstants.SEP6_TRANSACTION_CREATED,
          MetricConstants.TYPE,
          MetricConstants.TV_SEP6_DEPOSIT);
  private final Counter sep6DepositExchangeCounter =
      counter(
          MetricConstants.SEP6_TRANSACTION_CREATED,
          MetricConstants.TYPE,
          MetricConstants.TV_SEP6_DEPOSIT_EXCHANGE);

  public Sep6Service(
      AppConfig appConfig,
      Sep6Config sep6Config,
      Sep38Config sep38Config,
      AssetService assetService,
      RequestValidator requestValidator,
      ClientFinder clientFinder,
      Sep6TransactionStore txnStore,
      ExchangeAmountsCalculator exchangeAmountsCalculator,
      EventService eventService,
      MoreInfoUrlConstructor moreInfoUrlConstructor) {
    this.appConfig = appConfig;
    this.sep6Config = sep6Config;
    this.sep38Config = sep38Config;
    this.assetService = assetService;
    this.requestValidator = requestValidator;
    this.clientFinder = clientFinder;
    this.txnStore = txnStore;
    this.exchangeAmountsCalculator = exchangeAmountsCalculator;
    this.eventSession =
        eventService.createSession(this.getClass().getName(), EventService.EventQueue.TRANSACTION);
    this.infoResponse = buildInfoResponse();
    this.moreInfoUrlConstructor = moreInfoUrlConstructor;
  }

  public InfoResponse getInfo() {
    return infoResponse;
  }

  public StartDepositResponse deposit(Sep10Jwt token, StartDepositRequest request)
      throws AnchorException {
    sep6TransactionRequestedCounter.increment();

    // Pre-validation
    if (token == null) {
      throw new SepNotAuthorizedException("missing token");
    }
    if (request == null) {
      throw new SepValidationException("missing request");
    }

    StellarAssetInfo asset = requestValidator.getDepositAsset(request.getAssetCode());
    String fundingMethod =
        request.getFundingMethod() != null ? request.getFundingMethod() : request.getType();
    requestValidator.validateTypes(
        fundingMethod, asset.getCode(), asset.getSep6().getDeposit().getMethods());

    if (request.getAmount() != null) {
      requestValidator.validateAmount(
          request.getAmount(),
          asset.getCode(),
          asset.getSignificantDecimals(),
          asset.getSep6().getDeposit().getMinAmount(),
          asset.getSep6().getDeposit().getMaxAmount());
    }
    requestValidator.validateAccount(request.getAccount());

    Memo memo = makeMemo(request.getMemo(), request.getMemoType());
    String id = SepHelper.generateSepTransactionId();

    Sep6TransactionBuilder builder =
        new Sep6TransactionBuilder(txnStore)
            .id(id)
            .transactionId(id)
            .status(SepTransactionStatus.INCOMPLETE.toString())
            .kind(Sep6Transaction.Kind.DEPOSIT.toString())
            .type(fundingMethod)
            .assetCode(request.getAssetCode())
            .assetIssuer(asset.getIssuer())
            .amountExpected(request.getAmount())
            .startedAt(Instant.now())
            .userActionRequiredBy(
                sep6Config.getInitialUserDeadlineSeconds() == null
                    ? null
                    : Instant.now().plusSeconds(sep6Config.getInitialUserDeadlineSeconds()))
            .sep10Account(token.getAccount())
            .sep10AccountMemo(token.getAccountMemo())
            .toAccount(request.getAccount())
            .clientDomain(token.getClientDomain())
            .clientName(clientFinder.getClientName(token));

    if (memo != null) {
      builder.memo(memo.toString());
      builder.memoType(SepHelper.memoTypeString(memoType(memo)));
    }

    Sep6Transaction txn = builder.build();
    txnStore.save(txn);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("6")
            .type(AnchorEvent.Type.TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn, assetService))
            .build());

    sep6DepositCounter.increment();
    return StartDepositResponse.builder()
        .how("Check the transaction for more information about how to deposit.")
        .id(txn.getId())
        .build();
  }

  public StartDepositResponse depositExchange(Sep10Jwt token, StartDepositExchangeRequest request)
      throws AnchorException {
    sep6TransactionRequestedCounter.increment();

    if (token == null) {
      throw new SepNotAuthorizedException("missing token");
    }
    if (request == null) {
      throw new SepValidationException("missing request");
    }
    if (!sep38Config.isEnabled()) {
      throw new SepNotImplementedException(
          "The deposit-exchange is not supported because sep38.enabled is set to false.");
    }

    AssetInfo sellAsset = assetService.getAssetById(request.getSourceAsset());
    if (sellAsset == null) {
      throw new SepValidationException(
          String.format("invalid operation for asset %s", request.getSourceAsset()));
    }

    StellarAssetInfo buyAsset = requestValidator.getDepositAsset(request.getDestinationAsset());
    String fundingMethod =
        request.getFundingMethod() != null ? request.getFundingMethod() : request.getType();
    requestValidator.validateTypes(
        fundingMethod, buyAsset.getCode(), buyAsset.getSep6().getDeposit().getMethods());
    requestValidator.validateAmount(
        request.getAmount(),
        buyAsset.getCode(),
        buyAsset.getSignificantDecimals(),
        buyAsset.getSep6().getDeposit().getMinAmount(),
        buyAsset.getSep6().getDeposit().getMaxAmount());
    requestValidator.validateAccount(request.getAccount());

    Amounts amounts;
    if (request.getQuoteId() != null) {
      amounts =
          exchangeAmountsCalculator.calculateFromQuote(
              request.getQuoteId(), sellAsset, request.getAmount());
    } else {
      // TODO(philip): remove this
      // If a quote is not provided, set the fee and out amounts to 0.
      // The business server should use the request_offchain_funds RPC to update the amounts.
      amounts =
          Amounts.builder()
              .amountIn(request.getAmount())
              .amountInAsset(sellAsset.getId())
              .amountOut("0")
              .amountOutAsset(buyAsset.getId())
              .feeDetails(new FeeDetails("0", sellAsset.getId(), null))
              .build();
    }

    Memo memo = makeMemo(request.getMemo(), request.getMemoType());
    String id = SepHelper.generateSepTransactionId();

    Sep6TransactionBuilder builder =
        new Sep6TransactionBuilder(txnStore)
            .id(id)
            .transactionId(id)
            .status(SepTransactionStatus.INCOMPLETE.toString())
            .kind(Sep6Transaction.Kind.DEPOSIT_EXCHANGE.toString())
            .type(fundingMethod)
            .assetCode(buyAsset.getCode())
            .assetIssuer(buyAsset.getIssuer())
            .amountIn(amounts.getAmountIn())
            .amountInAsset(amounts.getAmountInAsset())
            .amountOut(amounts.getAmountOut())
            .amountOutAsset(amounts.getAmountOutAsset())
            .feeDetails(amounts.feeDetails)
            .amountExpected(request.getAmount())
            .startedAt(Instant.now())
            .userActionRequiredBy(
                sep6Config.getInitialUserDeadlineSeconds() == null
                    ? null
                    : Instant.now().plusSeconds(sep6Config.getInitialUserDeadlineSeconds()))
            .sep10Account(token.getAccount())
            .sep10AccountMemo(token.getAccountMemo())
            .toAccount(request.getAccount())
            .clientDomain(token.getClientDomain())
            .clientName(clientFinder.getClientName(token))
            .quoteId(request.getQuoteId());

    if (memo != null) {
      builder.memo(memo.toString());
      builder.memoType(SepHelper.memoTypeString(memoType(memo)));
    }

    Sep6Transaction txn = builder.build();
    txnStore.save(txn);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("6")
            .type(AnchorEvent.Type.TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn, assetService))
            .build());

    sep6DepositExchangeCounter.increment();
    return StartDepositResponse.builder()
        .how("Check the transaction for more information about how to deposit.")
        .id(id)
        .build();
  }

  public StartWithdrawResponse withdraw(Sep10Jwt token, StartWithdrawRequest request)
      throws AnchorException {
    sep6TransactionRequestedCounter.increment();

    // Pre-validation
    if (token == null) {
      throw new SepNotAuthorizedException("missing token");
    }
    if (request == null) {
      throw new SepValidationException("missing request");
    }

    StellarAssetInfo asset = requestValidator.getWithdrawAsset(request.getAssetCode());
    String fundingMethod =
        request.getFundingMethod() != null ? request.getFundingMethod() : request.getType();
    requestValidator.validateTypes(
        fundingMethod, asset.getCode(), asset.getSep6().getWithdraw().getMethods());

    if (request.getAmount() != null) {
      requestValidator.validateAmount(
          request.getAmount(),
          asset.getCode(),
          asset.getSignificantDecimals(),
          asset.getSep6().getWithdraw().getMinAmount(),
          asset.getSep6().getWithdraw().getMaxAmount());
    }
    String sourceAccount = request.getAccount() != null ? request.getAccount() : token.getAccount();
    requestValidator.validateAccount(sourceAccount);

    String id = SepHelper.generateSepTransactionId();

    Sep6TransactionBuilder builder =
        new Sep6TransactionBuilder(txnStore)
            .id(id)
            .transactionId(id)
            .status(SepTransactionStatus.INCOMPLETE.toString())
            .kind(Sep6Transaction.Kind.WITHDRAWAL.toString())
            .type(fundingMethod)
            .assetCode(request.getAssetCode())
            .assetIssuer(asset.getIssuer())
            .amountIn(request.getAmount())
            .amountInAsset(asset.getId())
            .amountExpected(request.getAmount())
            .startedAt(Instant.now())
            .userActionRequiredBy(
                sep6Config.getInitialUserDeadlineSeconds() == null
                    ? null
                    : Instant.now().plusSeconds(sep6Config.getInitialUserDeadlineSeconds()))
            .sep10Account(token.getAccount())
            .sep10AccountMemo(token.getAccountMemo())
            .fromAccount(sourceAccount)
            .clientDomain(token.getClientDomain())
            .clientName(clientFinder.getClientName(token))
            .refundMemo(request.getRefundMemo())
            .refundMemoType(request.getRefundMemoType());

    Sep6Transaction txn = builder.build();
    txnStore.save(txn);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("6")
            .type(AnchorEvent.Type.TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn, assetService))
            .build());

    sep6WithdrawalCounter.increment();
    return StartWithdrawResponse.builder().id(txn.getId()).build();
  }

  public StartWithdrawResponse withdrawExchange(
      Sep10Jwt token, StartWithdrawExchangeRequest request) throws AnchorException {
    sep6TransactionRequestedCounter.increment();

    // Pre-validation
    if (token == null) {
      throw new SepNotAuthorizedException("missing token");
    }
    if (request == null) {
      throw new SepValidationException("missing request");
    }
    if (!sep38Config.isEnabled()) {
      throw new SepNotImplementedException(
          "The withdraw-exchange is not supported because sep38.enabled is set to false.");
    }

    AssetInfo buyAsset = assetService.getAssetById(request.getDestinationAsset());
    if (buyAsset == null) {
      throw new SepValidationException(
          String.format("invalid operation for asset %s", request.getDestinationAsset()));
    }

    StellarAssetInfo sellAsset = requestValidator.getWithdrawAsset(request.getSourceAsset());
    String fundingMethod =
        request.getFundingMethod() != null ? request.getFundingMethod() : request.getType();
    requestValidator.validateTypes(
        fundingMethod, sellAsset.getCode(), sellAsset.getSep6().getWithdraw().getMethods());
    requestValidator.validateAmount(
        request.getAmount(),
        sellAsset.getCode(),
        sellAsset.getSignificantDecimals(),
        sellAsset.getSep6().getWithdraw().getMinAmount(),
        sellAsset.getSep6().getWithdraw().getMaxAmount());
    String sourceAccount = request.getAccount() != null ? request.getAccount() : token.getAccount();
    requestValidator.validateAccount(sourceAccount);

    String id = SepHelper.generateSepTransactionId();

    Amounts amounts;
    if (request.getQuoteId() != null) {
      amounts =
          exchangeAmountsCalculator.calculateFromQuote(
              request.getQuoteId(), sellAsset, request.getAmount());
    } else {
      // TODO(philip): remove this
      // If a quote is not provided, set the fee and out amounts to 0.
      // The business server should use the request_onchain_funds RPC to update the amounts.
      amounts =
          Amounts.builder()
              .amountIn(request.getAmount())
              .amountInAsset(sellAsset.getId())
              .amountOut("0")
              .amountOutAsset(buyAsset.getId())
              .feeDetails(new FeeDetails("0", sellAsset.getId(), null))
              .build();
    }

    Sep6TransactionBuilder builder =
        new Sep6TransactionBuilder(txnStore)
            .id(id)
            .transactionId(id)
            .status(SepTransactionStatus.INCOMPLETE.toString())
            .kind(Sep6Transaction.Kind.WITHDRAWAL_EXCHANGE.toString())
            .type(fundingMethod)
            .assetCode(sellAsset.getCode())
            .assetIssuer(sellAsset.getIssuer())
            .amountIn(amounts.getAmountIn())
            .amountInAsset(amounts.getAmountInAsset())
            .amountOut(amounts.getAmountOut())
            .amountOutAsset(amounts.getAmountOutAsset())
            .feeDetails(amounts.getFeeDetails())
            .amountExpected(request.getAmount())
            .startedAt(Instant.now())
            .userActionRequiredBy(
                sep6Config.getInitialUserDeadlineSeconds() == null
                    ? null
                    : Instant.now().plusSeconds(sep6Config.getInitialUserDeadlineSeconds()))
            .sep10Account(token.getAccount())
            .sep10AccountMemo(token.getAccountMemo())
            .fromAccount(sourceAccount)
            .clientDomain(token.getClientDomain())
            .clientName(clientFinder.getClientName(token))
            .refundMemo(request.getRefundMemo())
            .refundMemoType(request.getRefundMemoType())
            .quoteId(request.getQuoteId());

    Sep6Transaction txn = builder.build();
    txnStore.save(txn);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("6")
            .type(AnchorEvent.Type.TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn, assetService))
            .build());

    sep6WithdrawalExchangeCounter.increment();
    return StartWithdrawResponse.builder().id(txn.getId()).build();
  }

  public GetTransactionsResponse findTransactions(Sep10Jwt token, GetTransactionsRequest request)
      throws SepException {
    // Pre-validation
    if (token == null) {
      throw new SepNotAuthorizedException("missing token");
    }
    if (request == null) {
      throw new SepValidationException("missing request");
    }
    if (!request.getAccount().equals(token.getAccount())) {
      throw new SepNotAuthorizedException("account does not match token");
    }
    if (assetService.getAsset(request.getAssetCode()) == null) {
      throw new SepValidationException(
          String.format("asset code %s not supported", request.getAssetCode()));
    }

    // Query the transaction store
    List<Sep6Transaction> transactions =
        txnStore.findTransactions(token.getAccount(), token.getAccountMemo(), request);
    List<Sep6TransactionResponse> responses = new ArrayList<>();
    for (Sep6Transaction txn : transactions) {
      String lang = validateLanguage(appConfig, request.getLang());
      Sep6TransactionResponse tr = Sep6TransactionUtils.fromTxn(txn, moreInfoUrlConstructor, lang);
      responses.add(tr);
    }

    sep6TransactionQueriedCounter.increment();
    return new GetTransactionsResponse(responses);
  }

  public GetTransactionResponse findTransaction(Sep10Jwt token, GetTransactionRequest request)
      throws AnchorException {
    // Pre-validation
    if (token == null) {
      throw new SepNotAuthorizedException("missing token");
    }
    if (request == null) {
      throw new SepValidationException("missing request");
    }

    // Query the transaction store
    Sep6Transaction txn;
    if (request.getId() != null) {
      txn = txnStore.findByTransactionId(request.getId());
    } else if (request.getStellarTransactionId() != null) {
      txn = txnStore.findByStellarTransactionId(request.getStellarTransactionId());
    } else if (request.getExternalTransactionId() != null) {
      txn = txnStore.findByExternalTransactionId(request.getExternalTransactionId());
    } else {
      throw new SepValidationException(
          "One of id, stellar_transaction_id, or external_transaction_id is required");
    }

    // Validate the transaction
    if (txn == null) {
      throw new NotFoundException("transaction not found");
    }
    if (!Objects.equals(txn.getSep10Account(), token.getAccount())) {
      throw new NotFoundException("account does not match token");
    }
    if (!Objects.equals(txn.getSep10AccountMemo(), token.getAccountMemo())) {
      throw new NotFoundException("account memo does not match token");
    }

    sep6TransactionQueriedCounter.increment();
    String lang = validateLanguage(appConfig, request.getLang());
    return new GetTransactionResponse(
        Sep6TransactionUtils.fromTxn(txn, moreInfoUrlConstructor, lang));
  }

  InfoResponse buildInfoResponse() {
    InfoResponse response =
        InfoResponse.builder()
            .deposit(new HashMap<>())
            .depositExchange(new HashMap<>())
            .withdraw(new HashMap<>())
            .withdrawExchange(new HashMap<>())
            .fee(
                FeeResponse.builder()
                    .enabled(false)
                    .description("Fee endpoint is not supported.")
                    .build())
            .transactions(
                TransactionsResponse.builder().enabled(true).authenticationRequired(true).build())
            .transaction(
                TransactionResponse.builder().enabled(true).authenticationRequired(true).build())
            .features(
                FeaturesResponse.builder()
                    .accountCreation(sep6Config.getFeatures().isAccountCreation())
                    .claimableBalances(sep6Config.getFeatures().isClaimableBalances())
                    .build())
            .build();

    for (StellarAssetInfo asset : assetService.getStellarAssets()) {
      if (isDepositEnabled(asset.getSep6())) {
        List<String> methods = asset.getSep6().getDeposit().getMethods();
        AssetInfo.Field type =
            AssetInfo.Field.builder()
                .description("type of deposit to make")
                .choices(methods)
                .optional(true)
                .build();

        DepositAssetResponse deposit =
            DepositAssetResponse.builder()
                .enabled(true)
                .authenticationRequired(true)
                .minAmount(asset.getSep6().getDeposit().getMinAmount())
                .maxAmount(asset.getSep6().getDeposit().getMaxAmount())
                .fundingMethods(methods)
                .fields(ImmutableMap.of("type", type))
                .build();
        response.getDeposit().put(asset.getCode(), deposit);
        if (sep38Config.isEnabled()) {
          response.getDepositExchange().put(asset.getCode(), deposit);
        }
      }

      if (isWithdrawEnabled(asset.getSep6())) {
        List<String> methods = asset.getSep6().getWithdraw().getMethods();
        Map<String, WithdrawType> types = new HashMap<>();
        for (String method : methods) {
          types.put(method, WithdrawType.builder().fields(new HashMap<>()).build());
        }
        WithdrawAssetResponse withdraw =
            WithdrawAssetResponse.builder()
                .enabled(true)
                .authenticationRequired(true)
                .minAmount(asset.getSep6().getWithdraw().getMinAmount())
                .maxAmount(asset.getSep6().getWithdraw().getMaxAmount())
                .fundingMethods(methods)
                .types(types)
                .build();

        response.getWithdraw().put(asset.getCode(), withdraw);
        if (sep38Config.isEnabled()) {
          response.getWithdrawExchange().put(asset.getCode(), withdraw);
        }
      }
    }
    return response;
  }
}
