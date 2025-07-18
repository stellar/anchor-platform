package org.stellar.anchor.sep24;

import static io.micrometer.core.instrument.Metrics.counter;
import static org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_CREATED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.INCOMPLETE;
import static org.stellar.anchor.api.sep.sep24.InfoResponse.FeatureFlagResponse;
import static org.stellar.anchor.api.sep.sep24.InfoResponse.FeeResponse;
import static org.stellar.anchor.event.EventService.EventQueue.TRANSACTION;
import static org.stellar.anchor.sep24.Sep24Helper.fromTxn;
import static org.stellar.anchor.sep24.Sep24Transaction.Kind.*;
import static org.stellar.anchor.util.Log.debug;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.info;
import static org.stellar.anchor.util.Log.infoF;
import static org.stellar.anchor.util.Log.shorter;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MemoHelper.makeMemo;
import static org.stellar.anchor.util.MemoHelper.memoType;
import static org.stellar.anchor.util.MetricConstants.*;
import static org.stellar.anchor.util.SepHelper.generateSepTransactionId;
import static org.stellar.anchor.util.SepHelper.memoTypeString;
import static org.stellar.anchor.util.SepLanguageHelper.validateLanguage;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.micrometer.core.instrument.Counter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.DepositWithdrawOperation;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.sep.sep24.GetTransactionRequest;
import org.stellar.anchor.api.sep.sep24.GetTransactionsRequest;
import org.stellar.anchor.api.sep.sep24.GetTransactionsResponse;
import org.stellar.anchor.api.sep.sep24.InfoResponse;
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse;
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse;
import org.stellar.anchor.api.sep.sep24.TransactionResponse;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.client.ClientFinder;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.client.CustodialClient;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.Sep24Config;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.util.*;
import org.stellar.anchor.util.ExchangeAmountsCalculator;
import org.stellar.anchor.util.SepRequestValidator;
import org.stellar.sdk.Memo;

public class Sep24Service {

  public static final List<String> INTERACTIVE_URL_JWT_REQUIRED_FIELDS_FROM_REQUEST =
      List.of("amount", "client_domain", "lang", "customer_id");
  public static String ERR_TOKEN_ACCOUNT_MISMATCH = "'account' does not match the one in the token";
  final AppConfig appConfig;
  final Sep24Config sep24Config;
  final ClientService clientService;
  final AssetService assetService;
  final SepRequestValidator requestValidator;
  final JwtService jwtService;
  final ClientFinder clientFinder;
  final Sep24TransactionStore txnStore;
  final EventService.Session eventSession;
  final InteractiveUrlConstructor interactiveUrlConstructor;
  final MoreInfoUrlConstructor moreInfoUrlConstructor;
  final ExchangeAmountsCalculator exchangeAmountsCalculator;
  final Counter sep24TransactionRequestedCounter =
      counter(MetricConstants.SEP24_TRANSACTION_REQUESTED);
  final Counter sep24TransactionQueriedCounter = counter(MetricConstants.SEP24_TRANSACTION_QUERIED);
  final Counter sep24WithdrawalCounter =
      counter(
          MetricConstants.SEP24_TRANSACTION_CREATED,
          MetricConstants.TYPE,
          MetricConstants.TV_SEP24_WITHDRAWAL);
  final Counter sep24DepositCounter =
      counter(
          MetricConstants.SEP24_TRANSACTION_CREATED,
          MetricConstants.TYPE,
          MetricConstants.TV_SEP24_DEPOSIT);

  public Sep24Service(
      AppConfig appConfig,
      Sep24Config sep24Config,
      ClientService clientsService,
      AssetService assetService,
      SepRequestValidator requestValidator,
      JwtService jwtService,
      ClientFinder clientFinder,
      Sep24TransactionStore txnStore,
      EventService eventService,
      InteractiveUrlConstructor interactiveUrlConstructor,
      MoreInfoUrlConstructor moreInfoUrlConstructor,
      ExchangeAmountsCalculator exchangeAmountsCalculator) {
    debug("appConfig:", appConfig);
    debug("sep24Config:", sep24Config);
    this.appConfig = appConfig;
    this.sep24Config = sep24Config;
    this.clientService = clientsService;
    this.assetService = assetService;
    this.requestValidator = requestValidator;
    this.jwtService = jwtService;
    this.clientFinder = clientFinder;
    this.txnStore = txnStore;
    this.eventSession = eventService.createSession(this.getClass().getName(), TRANSACTION);
    this.interactiveUrlConstructor = interactiveUrlConstructor;
    this.moreInfoUrlConstructor = moreInfoUrlConstructor;
    this.exchangeAmountsCalculator = exchangeAmountsCalculator;
    info("Sep24Service initialized.");
  }

  public InteractiveTransactionResponse withdraw(
      WebAuthJwt token, Map<String, String> withdrawRequest)
      throws AnchorException, MalformedURLException, URISyntaxException {
    info("Creating withdrawal transaction.");
    // increment counter
    sep24TransactionRequestedCounter.increment();
    if (token == null) {
      info("missing SEP-10 token");
      throw new SepValidationException("missing token");
    }

    if (withdrawRequest == null) {
      info("missing withdraw request");
      throw new SepValidationException("no request");
    }

    infoF(
        "Sep24.withdraw. account={}, memo={}", shorter(token.getAccount()), token.getAccountMemo());
    String assetCode = withdrawRequest.get("asset_code");
    String assetIssuer = withdrawRequest.get("asset_issuer");
    String sourceAccount = withdrawRequest.get("account");
    String strAmount = withdrawRequest.get("amount");

    String lang = validateLanguage(appConfig, withdrawRequest.get("lang"));
    debugF("language: {}", lang);

    if (assetCode == null) {
      info("missing 'asset_code'");
      throw new SepValidationException("missing 'asset_code'");
    }

    if (isEmpty(sourceAccount)) {
      sourceAccount = token.getAccount();
    }

    // Verify that the asset code exists in our database, with withdraw enabled.
    StellarAssetInfo asset = (StellarAssetInfo) assetService.getAsset(assetCode, assetIssuer);
    debugF("Asset: {}", asset);
    if (asset == null || !AssetHelper.isWithdrawEnabled(asset.getSep24())) {
      infoF("invalid operation for asset {}", assetCode);
      throw new SepValidationException(String.format("invalid operation for asset %s", assetCode));
    }

    // Validate min amount
    DepositWithdrawOperation sep24WithdrawInfo = asset.getSep24().getWithdraw();
    Long minAmount = sep24WithdrawInfo.getMinAmount();
    if (strAmount != null && minAmount != null) {
      if (decimal(strAmount).compareTo(decimal(minAmount)) < 0) {
        infoF("invalid amount {}", strAmount);
        throw new SepValidationException(
            String.format("amount is less than asset's minimum limit: %s", strAmount));
      }
    }

    // Validate max amount
    Long maxAmount = sep24WithdrawInfo.getMaxAmount();
    if (strAmount != null && maxAmount != null) {
      if (decimal(strAmount).compareTo(decimal(maxAmount)) > 0) {
        infoF("invalid amount {}", strAmount);
        throw new SepValidationException(
            String.format("amount exceeds asset's maximum limit: %s", strAmount));
      }
    }

    // Validate sourceAccount
    requestValidator.validateAccount(sourceAccount);

    if (token.getClientDomain() != null)
      withdrawRequest.put("client_domain", token.getClientDomain());

    Memo memo = makeMemo(withdrawRequest.get("memo"), withdrawRequest.get("memo_type"));
    Memo refundMemo =
        makeMemo(withdrawRequest.get("refund_memo"), withdrawRequest.get("refund_memo_type"));
    String txnId = UUID.randomUUID().toString();
    Sep24TransactionBuilder builder =
        new Sep24TransactionBuilder(txnStore)
            .transactionId(txnId)
            .status(INCOMPLETE.toString())
            .kind(WITHDRAWAL.toString())
            .assetCode(assetCode)
            .assetIssuer(asset.getIssuer())
            .startedAt(Instant.now())
            .userActionRequiredBy(
                sep24Config.getInitialUserDeadlineSeconds() == null
                    ? null
                    : Instant.now().plusSeconds(sep24Config.getInitialUserDeadlineSeconds()))
            .webAuthAccount(token.getAccount())
            .webAuthAccountMemo(token.getAccountMemo())
            .fromAccount(sourceAccount)
            .toAccount(asset.getDistributionAccount())
            .clientDomain(token.getClientDomain())
            .clientName(clientFinder.getClientName(token));

    if (memo != null) {
      debug("transaction memo detected.", memo);
      builder.memo(memo.toString());
      builder.memoType(memoTypeString(memoType(memo)));
    }

    if (refundMemo != null) {
      debug("refund memo detected.", refundMemo);
      builder.refundMemo(refundMemo.toString());
      builder.refundMemoType(memoTypeString(memoType(refundMemo)));
    }

    String quoteId = withdrawRequest.get("quote_id");
    AssetInfo buyAsset = assetService.getAssetById(withdrawRequest.get("destination_asset"));
    if (quoteId != null) {
      validateAndPopulateQuote(
          quoteId, asset, buyAsset, strAmount, builder, WITHDRAWAL.toString(), txnId);
    } else {
      builder.amountExpected(strAmount);
      if (buyAsset != null) {
        builder.amountOut("0");
        builder.amountOutAsset(buyAsset.getId());
      }
    }

    Sep24Transaction txn = builder.build();
    txnStore.save(txn);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("24")
            .type(TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn, assetService))
            .build());

    infoF(
        "Saved withdraw transaction. from={}, amountIn={}, amountOut={}.",
        shorter(txn.getFromAccount()),
        txn.getAmountIn(),
        txn.getAmountOut());
    debug("Transaction details:", txn);
    InteractiveTransactionResponse response =
        new InteractiveTransactionResponse(
            "interactive_customer_info_needed",
            interactiveUrlConstructor.construct(txn, withdrawRequest, asset, token),
            txn.getTransactionId());

    // increment counter
    sep24WithdrawalCounter.increment();
    return response;
  }

  public InteractiveTransactionResponse deposit(
      WebAuthJwt token, Map<String, String> depositRequest)
      throws AnchorException, MalformedURLException, URISyntaxException {
    info("Creating deposit transaction.");
    counter(SEP24_TRANSACTION_REQUESTED, TYPE, TV_SEP24_DEPOSIT);
    if (token == null) {
      info("missing SEP-10 token");
      throw new SepValidationException("missing token");
    }

    if (depositRequest == null) {
      info("missing deposit request");
      throw new SepValidationException("no request");
    }

    infoF(
        "Sep24.deposit. account={}, memo={}", shorter(token.getAccount()), token.getAccountMemo());
    String assetCode = depositRequest.get("asset_code");
    String assetIssuer = depositRequest.get("asset_issuer");
    String destinationAccount = depositRequest.get("account");
    String strAmount = depositRequest.get("amount");

    String strClaimableSupported = depositRequest.get("claimable_balance_supported");
    boolean claimableSupported = false;
    if (strClaimableSupported != null) {
      claimableSupported = Boolean.parseBoolean(strClaimableSupported.toLowerCase(Locale.ROOT));
      debugF("claimable balance supported: {}", claimableSupported);
    }

    String lang = validateLanguage(appConfig, depositRequest.get("lang"));
    debugF("language: {}", lang);

    if (assetCode == null) {
      info("missing 'asset_code'");
      throw new SepValidationException("missing 'asset_code'");
    }

    if (isEmpty(destinationAccount)) {
      destinationAccount = token.getAccount();
    }

    if (!destinationAccount.equals(token.getAccount())) {
      CustodialClient clientConfig = clientService.getClientConfigBySigningKey(token.getAccount());
      if (clientConfig != null && clientConfig.getDestinationAccounts() != null) {
        if (!clientConfig.getDestinationAccounts().contains(destinationAccount)) {
          infoF(
              "The request account:{} for wallet:{} is not in the allowed destination accounts list",
              destinationAccount,
              clientConfig.getName());
          throw new SepValidationException("Provided 'account' is not allowed");
        }
      } else {
        if (clientConfig == null || !clientConfig.isAllowAnyDestination()) {
          infoF(
              "The request account:{} does not match the one in the token:{}",
              destinationAccount,
              token.getAccount());
          throw new SepValidationException(ERR_TOKEN_ACCOUNT_MISMATCH);
        }
      }
    }

    // Verify that the asset code exists in our database, with deposit enabled.
    StellarAssetInfo asset = (StellarAssetInfo) assetService.getAsset(assetCode, assetIssuer);
    if (asset == null || !AssetHelper.isDepositEnabled(asset.getSep24())) {
      infoF("invalid operation for asset {}", assetCode);
      throw new SepValidationException(String.format("invalid operation for asset %s", assetCode));
    }

    // Validate min amount
    DepositWithdrawOperation sep24DepositInfo = asset.getSep24().getDeposit();
    Long minAmount = sep24DepositInfo.getMinAmount();
    if (strAmount != null && minAmount != null) {
      if (decimal(strAmount).compareTo(decimal(minAmount)) < 0) {
        infoF("invalid amount {}", strAmount);
        throw new SepValidationException(
            String.format("amount is less than asset's minimum limit: %s", strAmount));
      }
    }

    // Validate max amount
    Long maxAmount = sep24DepositInfo.getMaxAmount();
    if (strAmount != null && maxAmount != null) {
      if (decimal(strAmount).compareTo(decimal(maxAmount)) > 0) {
        infoF("invalid amount {}", strAmount);
        throw new SepValidationException(
            String.format("amount exceeds asset's maximum limit: %s", strAmount));
      }
    }

    // validate destination account
    requestValidator.validateAccount(destinationAccount);

    if (token.getClientDomain() != null)
      depositRequest.put("client_domain", token.getClientDomain());

    Memo memo = makeMemo(depositRequest.get("memo"), depositRequest.get("memo_type"));
    String txnId = generateSepTransactionId();
    Sep24TransactionBuilder builder =
        new Sep24TransactionBuilder(txnStore)
            .transactionId(txnId)
            .status(INCOMPLETE.toString())
            .kind(DEPOSIT.toString())
            .assetCode(assetCode)
            .assetIssuer(depositRequest.get("asset_issuer"))
            .startedAt(Instant.now())
            .userActionRequiredBy(
                sep24Config.getInitialUserDeadlineSeconds() == null
                    ? null
                    : Instant.now().plusSeconds(sep24Config.getInitialUserDeadlineSeconds()))
            .webAuthAccount(token.getAccount())
            .webAuthAccountMemo(token.getAccountMemo())
            .toAccount(destinationAccount)
            .clientDomain(token.getClientDomain())
            .clientName(clientFinder.getClientName(token))
            .claimableBalanceSupported(claimableSupported);

    if (memo != null) {
      debug("transaction memo detected.", memo);
      builder.memo(memo.toString());
      builder.memoType(memoTypeString(memoType(memo)));
    }

    String quoteId = depositRequest.get("quote_id");
    AssetInfo sellAsset = assetService.getAssetById(depositRequest.get("source_asset"));
    if (quoteId != null) {
      validateAndPopulateQuote(
          quoteId, sellAsset, asset, strAmount, builder, DEPOSIT.toString(), txnId);
    } else {
      builder.amountExpected(strAmount);
      if (sellAsset != null) {
        builder.amountIn("0");
        builder.amountInAsset(sellAsset.getId());
      }
    }

    Sep24Transaction txn = builder.build();
    txnStore.save(txn);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("24")
            .type(TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn, assetService))
            .build());

    infoF(
        "Saved deposit transaction. to={}, amountIn={}, amountOut={}.",
        shorter(txn.getToAccount()),
        txn.getAmountIn(),
        txn.getAmountOut());
    debug("Transaction details:", txn);

    InteractiveTransactionResponse response =
        new InteractiveTransactionResponse(
            "interactive_customer_info_needed",
            interactiveUrlConstructor.construct(txn, depositRequest, asset, token),
            txn.getTransactionId());
    // increment counter
    sep24DepositCounter.increment();
    return response;
  }

  public GetTransactionsResponse findTransactions(WebAuthJwt token, GetTransactionsRequest txReq)
      throws SepException, MalformedURLException, URISyntaxException {
    if (token == null) {
      info("missing SEP-10 token");
      throw new SepNotAuthorizedException("missing token");
    }

    String assetCode = txReq.getAssetCode();
    String assetIssuer = null;

    if (assetCode.contains("stellar:")) {
      String[] parsed = txReq.getAssetCode().split(":");
      if (parsed.length == 3) {
        assetCode = parsed[1];
        assetIssuer = parsed[2];
      } else if (parsed.length == 2) {
        assetCode = parsed[1];
      }
    }

    infoF("findTransactions. account={}", shorter(token.getAccount()));
    if (assetService.getAsset(assetCode, assetIssuer) == null) {
      infoF(
          "asset code:{} not supported",
          (txReq.getAssetCode() == null) ? "null" : txReq.getAssetCode());
      throw new SepValidationException("asset code not supported");
    }
    List<Sep24Transaction> txns =
        txnStore.findTransactions(token.getAccount(), token.getAccountMemo(), txReq);
    GetTransactionsResponse result = new GetTransactionsResponse();
    List<TransactionResponse> list = new ArrayList<>();
    debugF("found {} transactions", txns.size());
    for (Sep24Transaction txn : txns) {
      String lang = validateLanguage(appConfig, txReq.getLang());
      TransactionResponse transactionResponse =
          fromTxn(assetService, moreInfoUrlConstructor, txn, lang);
      list.add(transactionResponse);
    }
    result.setTransactions(list);
    // increment counter
    sep24TransactionQueriedCounter.increment();
    return result;
  }

  public Sep24GetTransactionResponse findTransaction(WebAuthJwt token, GetTransactionRequest txReq)
      throws SepException, IOException, URISyntaxException {
    if (token == null) {
      info("missing SEP-10 token");
      throw new SepNotAuthorizedException("missing token");
    }

    if (txReq == null) {
      info("missing request object");
      throw new SepValidationException("missing request object");
    }

    infoF("findTransaction. account={}", shorter(token.getAccount()));

    Sep24Transaction txn;
    if (txReq.getId() != null) {
      infoF("id={}", txReq.getId());
      txn = txnStore.findByTransactionId(txReq.getId());
    } else if (txReq.getStellarTransactionId() != null) {
      infoF("stellarTransactionId={}", shorter(txReq.getStellarTransactionId()));
      txn = txnStore.findByStellarTransactionId(txReq.getStellarTransactionId());
    } else if (txReq.getExternalTransactionId() != null) {
      infoF("externalTransactionId={}", shorter(txReq.getExternalTransactionId()));
      txn = txnStore.findByExternalTransactionId(txReq.getExternalTransactionId());
    } else {
      throw new SepValidationException(
          "One of id, stellar_transaction_id or external_transaction_id is required.");
    }

    // We should not return the transaction that belongs to other accounts.
    if (txn == null || !txn.getWebAuthAccount().equals(token.getAccount())) {
      infoF("no transactions found with account:{}", token.getAccount());
      throw new SepNotFoundException("transaction not found");
    }

    // If the token has a memo, make sure the transaction belongs to the account
    // with the same memo.
    if (token.getAccountMemo() != null
        && txn.getWebAuthAccount().equals(token.getAccount() + ":" + token.getAccountMemo())) {
      infoF(
          "no transactions found with account:{} memo:{}",
          token.getAccount(),
          token.getAccountMemo());
      throw new SepNotFoundException("transaction not found");
    }
    // increment counter
    sep24TransactionQueriedCounter.increment();
    String lang = validateLanguage(appConfig, txReq.getLang());
    return Sep24GetTransactionResponse.of(fromTxn(assetService, moreInfoUrlConstructor, txn, lang));
  }

  public InfoResponse getInfo() {
    info("Getting Sep24 info");
    List<StellarAssetInfo> assets = assetService.getStellarAssets();
    debugF("{} assets found", assets.size());

    Map<String, InfoResponse.OperationResponse> depositMap = new HashMap<>();
    Map<String, InfoResponse.OperationResponse> withdrawMap = new HashMap<>();
    for (StellarAssetInfo asset : assets) {
      // iso4217 assets do not have deposit/withdraw configurations
      if (AssetHelper.isDepositEnabled(asset.getSep24()))
        depositMap.put(
            asset.getCode(),
            InfoResponse.OperationResponse.fromAssetOperation(asset.getSep24().getDeposit()));
      if (AssetHelper.isWithdrawEnabled(asset.getSep24()))
        withdrawMap.put(
            asset.getCode(),
            InfoResponse.OperationResponse.fromAssetOperation(asset.getSep24().getWithdraw()));
    }

    return InfoResponse.builder()
        .deposit(depositMap)
        .withdraw(withdrawMap)
        .fee(new FeeResponse(false))
        .features(
            new FeatureFlagResponse(
                sep24Config.getFeatures().getAccountCreation(),
                sep24Config.getFeatures().getClaimableBalances()))
        .build();
  }

  public void validateAndPopulateQuote(
      String quoteId,
      AssetInfo sellAsset,
      AssetInfo buyAsset,
      String strAmount,
      Sep24TransactionBuilder builder,
      String kind,
      String txnId)
      throws AnchorException {
    Sep38Quote quote =
        exchangeAmountsCalculator.validateQuoteAgainstRequestInfo(
            quoteId, sellAsset, buyAsset, strAmount);

    debugF("Updating transaction ({}) with quote ({})", txnId, quoteId);
    builder.quoteId(quoteId);
    builder.amountExpected(quote.getSellAmount());
    builder.amountIn(quote.getSellAmount());
    builder.amountInAsset(quote.getSellAsset());
    builder.amountOut(quote.getBuyAmount());
    builder.amountOutAsset(quote.getBuyAsset());
    builder.feeDetails(quote.getFee());
  }
}
