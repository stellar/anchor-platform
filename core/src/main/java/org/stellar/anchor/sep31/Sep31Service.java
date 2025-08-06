package org.stellar.anchor.sep31;

import static io.micrometer.core.instrument.Metrics.counter;
import static org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_CREATED;
import static org.stellar.anchor.api.sep.sep31.Sep31InfoResponse.AssetResponse;
import static org.stellar.anchor.config.Sep31Config.PaymentType.STRICT_SEND;
import static org.stellar.anchor.event.EventService.EventQueue.TRANSACTION;
import static org.stellar.anchor.util.Log.debug;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.info;
import static org.stellar.anchor.util.Log.infoF;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;
import static org.stellar.anchor.util.MetricConstants.SEP31_TRANSACTION_CREATED;
import static org.stellar.anchor.util.MetricConstants.SEP31_TRANSACTION_PATCHED;
import static org.stellar.anchor.util.SepHelper.*;
import static org.stellar.anchor.util.SepLanguageHelper.validateLanguage;

import io.micrometer.core.instrument.Counter;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.Data;
import lombok.SneakyThrows;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.callback.*;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.exception.Sep31MissingFieldException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse;
import org.stellar.anchor.api.sep.sep31.Sep31InfoResponse;
import org.stellar.anchor.api.sep.sep31.Sep31PatchTransactionRequest;
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest;
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.config.Sep10Config;
import org.stellar.anchor.config.Sep31Config;
import org.stellar.anchor.config.StellarNetworkConfig;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.TransactionMapper;

public class Sep31Service {
  private final StellarNetworkConfig stellarNetworkConfig;
  private final Sep10Config sep10Config;
  private final Sep31Config sep31Config;
  private final Sep31TransactionStore sep31TransactionStore;
  private final Sep38QuoteStore sep38QuoteStore;
  private final ClientService clientService;
  private final AssetService assetService;
  private final RateIntegration rateIntegration;
  private final Sep31InfoResponse infoResponse;
  private final EventService.Session eventSession;
  private final Counter sep31TransactionCreatedCounter = counter(SEP31_TRANSACTION_CREATED);
  private final Counter sep31TransactionPatchedCounter = counter(SEP31_TRANSACTION_PATCHED);

  public Sep31Service(
      StellarNetworkConfig stellarNetworkConfig,
      Sep10Config sep10Config,
      Sep31Config sep31Config,
      Sep31TransactionStore sep31TransactionStore,
      Sep38QuoteStore sep38QuoteStore,
      ClientService clientService,
      AssetService assetService,
      RateIntegration rateIntegration,
      EventService eventService) {
    debug("appConfig:", stellarNetworkConfig);
    debug("sep31Config:", sep31Config);
    this.stellarNetworkConfig = stellarNetworkConfig;
    this.sep10Config = sep10Config;
    this.sep31Config = sep31Config;
    this.sep31TransactionStore = sep31TransactionStore;
    this.sep38QuoteStore = sep38QuoteStore;
    this.clientService = clientService;
    this.assetService = assetService;
    this.rateIntegration = rateIntegration;
    this.eventSession = eventService.createSession(this.getClass().getName(), TRANSACTION);
    this.infoResponse = sep31InfoResponseFromAssetInfoList(assetService.getAssets());
    Log.info("Sep31Service initialized.");
  }

  public Sep31InfoResponse getInfo() {
    return infoResponse;
  }

  @Transactional(rollbackOn = {AnchorException.class, RuntimeException.class})
  public Sep31PostTransactionResponse postTransaction(
      WebAuthJwt webAuthJwt, Sep31PostTransactionRequest request) throws AnchorException {
    Context.reset();
    Context.get().setRequest(request);
    Context.get().setWebAuthJwt(webAuthJwt);

    StellarAssetInfo assetInfo =
        (StellarAssetInfo) assetService.getAsset(request.getAssetCode(), request.getAssetIssuer());
    if (assetInfo == null) {
      // the asset is not supported.
      infoF("Asset: [{}:{}]", request.getAssetCode(), request.getAssetIssuer());
      throw new BadRequestException(
          String.format(
              "asset %s:%s is not supported.", request.getAssetCode(), request.getAssetIssuer()));
    }
    Context.get().setAsset(assetInfo);

    // Pre-validation
    validateAmount(request.getAmount());
    validateAmountLimit(
        "sell_",
        request.getAmount(),
        assetInfo.getSep31().getReceive().getMinAmount(),
        assetInfo.getSep31().getReceive().getMaxAmount());
    validateFundingMethod(
        assetInfo.getId(),
        request.getFundingMethod(),
        assetInfo.getSep31().getReceive().getMethods());
    validateLanguage(stellarNetworkConfig, request.getLang());

    /*
     * TODO:
     *  - conclude if we can drop the usage of `fields`.
     * TODO: if we can't stop using fields, we should:
     *  - check if `fields` are needed. If not, ignore this part of the code
     *  - make sure fields are not getting stored in the database
     *  - make sure fields are being forwarded in the TransactionEvent
     */
    if (request.getFields() == null) {
      infoF(
          "POST /transaction with id ({}) cannot have empty `fields`",
          webAuthJwt.getTransactionId());
      throw new BadRequestException("'fields' field cannot be empty");
    }
    Context.get().setTransactionFields(request.getFields().getTransaction());
    validateRequiredFields();

    // Validation that execute HTTP requests
    preValidateQuote();

    // Query the fee
    updateFee();

    // Get the creator's stellarId
    StellarId creatorStellarId =
        StellarId.builder()
            .account(
                Objects.requireNonNullElse(webAuthJwt.getMuxedAccount(), webAuthJwt.getAccount()))
            .memo(webAuthJwt.getAccountMemo())
            .build();

    Sep38Quote quote = Context.get().getQuote();
    FeeDetails feeDetails;

    if (quote != null) {
      feeDetails = quote.getFee();
    } else {
      Amount fee = Context.get().getFee();

      feeDetails = new FeeDetails(fee.getAmount(), fee.getAsset(), null);
    }

    Instant now = Instant.now();
    Sep31Transaction txn =
        new Sep31TransactionBuilder(sep31TransactionStore)
            .id(generateSepTransactionId())
            .status(SepTransactionStatus.PENDING_RECEIVER.getStatus())
            .fundingMethod(request.getFundingMethod())
            .statusEta(null)
            .feeDetails(feeDetails)
            .startedAt(now)
            .updatedAt(now) // this will be overwritten by the sep31TransactionStore#save method.
            .completedAt(null)
            .stellarTransactionId(null)
            .externalTransactionId(null)
            .requiredInfoMessage(null)
            .quoteId(request.getQuoteId())
            .clientDomain(webAuthJwt.getClientDomain())
            .clientName(getClientName())
            .requiredInfoUpdates(null)
            .fields(request.getFields().getTransaction())
            .refunded(null)
            .refunds(null)
            .senderId(Context.get().getRequest().getSenderId())
            .receiverId(Context.get().getRequest().getReceiverId())
            .creator(creatorStellarId)
            // updateAmounts will update these ⬇️
            .amountExpected(request.getAmount())
            .amountIn(request.getAmount())
            .amountInAsset(assetInfo.getId())
            .amountOut(null)
            .amountOutAsset(null)
            .build();

    Context.get().setTransaction(txn);
    updateAmounts();

    // TODO: open the connection with DB and only commit/save after publishing the event:
    Context.get().setTransaction(sep31TransactionStore.save(txn));
    txn = Context.get().getTransaction();

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("31")
            .type(TRANSACTION_CREATED)
            .transaction(TransactionMapper.toGetTransactionResponse(txn))
            .build());

    Sep31PostTransactionResponse response =
        Sep31PostTransactionResponse.builder().id(txn.getId()).build();
    // increment counter
    sep31TransactionCreatedCounter.increment();
    return response;
  }

  /**
   * Will update the amountIn, amountOut and amountFee, as well as the assets, taking into account
   * if quotes or if the {callbackApi}/fee endpoint was used.
   *
   * @throws AnchorException is something went wrong.
   */
  void updateAmounts() throws AnchorException {
    Sep31PostTransactionRequest request = Context.get().getRequest();
    if (request.getQuoteId() != null) {
      updateTxAmountsBasedOnQuote();
      return;
    }
    updateTxAmountsWhenNoQuoteWasUsed();
  }

  /**
   * updateTxAmountsBasedOnQuote will update the amountIn, amountOut and fee based on the quote.
   *
   * @throws ServerErrorException if the quote object is missing
   */
  void updateTxAmountsBasedOnQuote() throws ServerErrorException {
    Sep38Quote quote = Context.get().getQuote();
    if (quote == null) {
      infoF("Quote for transaction ({}) not found", Context.get().getTransaction().getId());
      throw new ServerErrorException("Quote not found.");
    }

    Sep31Transaction txn = Context.get().getTransaction();
    debugF("Updating transaction ({}) with quote ({})", txn.getId(), quote.getId());
    txn.setAmountInAsset(quote.getSellAsset());
    txn.setAmountIn(quote.getSellAmount());
    txn.setAmountExpected(quote.getSellAmount());
    txn.setAmountOutAsset(quote.getBuyAsset());
    txn.setAmountOut(quote.getBuyAmount());
    txn.setFeeDetails(quote.getFee());
  }

  /**
   * updateTxAmountsWhenNoQuoteWasUsed will update the transaction amountIn and amountOut based on
   * the request amount and the fee.
   */
  void updateTxAmountsWhenNoQuoteWasUsed() {
    Sep31PostTransactionRequest request = Context.get().getRequest();
    Sep31Transaction txn = Context.get().getTransaction();
    Amount feeResponse = Context.get().getFee();

    AssetInfo reqAsset = Context.get().getAsset();
    int scale = reqAsset.getSignificantDecimals();
    BigDecimal reqAmount = decimal(request.getAmount(), scale);
    BigDecimal fee = decimal(feeResponse.getAmount(), scale);

    BigDecimal amountIn;
    BigDecimal amountOut;
    boolean strictSend = sep31Config.getPaymentType() == STRICT_SEND;
    if (strictSend) {
      // amount_in = req.amount
      // amount_out = amount_in - amount fee
      amountIn = reqAmount;
      amountOut = amountIn.subtract(fee);
    } else {
      // amount_in = req.amount + fee
      // amount_out = req.amount
      amountIn = reqAmount.add(fee);
      amountOut = reqAmount;
    }
    debugF("Updating transaction ({}) with fee ({}) - reqAsset ({})", txn.getId(), fee, reqAsset);

    String amountInAsset = reqAsset.getId();
    String amountOutAsset = request.getDestinationAsset();

    boolean isSimpleQuote = Objects.equals(amountInAsset, amountOutAsset);

    // Update transaction
    txn.setAmountIn(formatAmount(amountIn, scale));
    txn.setAmountExpected(formatAmount(amountIn, scale));
    txn.setAmountInAsset(amountInAsset);
    if (isSimpleQuote) {
      txn.setAmountOut(formatAmount(amountOut, scale));
    }
    txn.setAmountOutAsset(amountOutAsset);

    // Update fee
    String feeStr = formatAmount(fee, scale);
    txn.setFeeDetails(new FeeDetails(feeStr, feeResponse.getAsset()));
    Context.get().getFee().setAmount(feeStr);
  }

  public Sep31GetTransactionResponse getTransaction(String id) throws AnchorException {
    if (Objects.toString(id, "").isEmpty()) {
      info("Empty 'id'");
      throw new BadRequestException("'id' is empty");
    }

    Sep31Transaction txn = sep31TransactionStore.findByTransactionId(id);
    if (txn == null) {
      infoF("Transaction ({}) not found", id);
      throw new NotFoundException(String.format("transaction (id=%s) not found", id));
    }

    return txn.toSep31GetTransactionResponse();
  }

  @Transactional(rollbackOn = {AnchorException.class, RuntimeException.class})
  public Sep31GetTransactionResponse patchTransaction(Sep31PatchTransactionRequest request)
      throws AnchorException {
    if (request == null) {
      infoF("request cannot be null");
      throw new BadRequestException("request cannot be null");
    }

    if (Objects.toString(request.getId(), "").isEmpty()) {
      infoF("id cannot be null or empty");
      throw new BadRequestException("id cannot be null nor empty");
    }

    Context.reset();

    Sep31Transaction txn = sep31TransactionStore.findByTransactionId(request.getId());
    if (txn == null) {
      infoF("Transaction ({}) not found", request.getId());
      throw new NotFoundException(String.format("transaction (id=%s) not found", request.getId()));
    }
    Context.get().setTransaction(txn);

    // validate if the transaction is in the pending_transaction_info_update status
    if (!Objects.equals(
        txn.getStatus(), SepTransactionStatus.PENDING_TRANSACTION_INFO_UPDATE.toString())) {
      infoF("Transaction ({}) does not need update", txn.getId());
      throw new BadRequestException(
          String.format("transaction (id=%s) does not need update", txn.getId()));
    }

    validatePatchTransactionFields(txn, request);

    request
        .getFields()
        .getTransaction()
        .forEach((fieldName, fieldValue) -> txn.getFields().put(fieldName, fieldValue));

    AssetInfo assetInfo = assetService.getAsset(txn.getAmountInAsset());
    Context.get().setAsset(assetInfo);
    Context.get().setTransactionFields(txn.getFields());
    validateRequiredFields();

    Sep31GetTransactionResponse response =
        sep31TransactionStore.save(txn).toSep31GetTransactionResponse();
    // increment counter
    sep31TransactionPatchedCounter.increment();
    return response;
  }

  /**
   * validatePatchTransactionFields will validate if the fields provided in the PATCH request are
   * expected by the transaction.
   *
   * @param txn is the Sep31Transaction already stored in the database.
   * @param request is the Sep31PatchTransactionRequest request
   * @throws BadRequestException if the stored request is not expecting any info update.
   * @throws BadRequestException if one of the provided fields is not being expected by the stored
   *     transaction.
   */
  void validatePatchTransactionFields(Sep31Transaction txn, Sep31PatchTransactionRequest request)
      throws BadRequestException {
    if (txn.getRequiredInfoUpdates() == null
        || txn.getRequiredInfoUpdates().getTransaction() == null) {
      infoF("Transaction ({}) is not expecting any updates", txn.getId());
      throw new BadRequestException(
          String.format("Transaction (%s) is not expecting any updates", txn.getId()));
    }

    Map<String, AssetInfo.Field> expectedFields = txn.getRequiredInfoUpdates().getTransaction();
    Map<String, String> requestFields = request.getFields().getTransaction();

    // validate if any of the fields from the request is not expected in the transaction.
    for (String fieldName : requestFields.keySet()) {
      if (!expectedFields.containsKey(fieldName)) {
        infoF("{} is not a expected field", fieldName);
        throw new BadRequestException(String.format("[%s] is not a expected field", fieldName));
      }
    }
  }

  /**
   * preValidateQuote will validate if the requested asset supports/requires quotes.
   *
   * <p>If quotes are supported and a `quote_id` was provided, this method will: - fetch the quote
   * using the callbackAPI. - validate if the quote is valid. - validate if the transaction fields
   * are compliant with the quote fields. - update the Context with the quote data.
   *
   * @throws BadRequestException if quotes are required but none was used in the request.
   * @throws BadRequestException if a quote with the provided id could not be found.
   * @throws BadRequestException if the transaction `amount` is different from the quote
   *     `sell_amount`.
   * @throws BadRequestException if the transaction `asset` is different from the quote
   *     `sell_asset`.
   */
  void preValidateQuote() throws BadRequestException {
    Sep31PostTransactionRequest request = Context.get().getRequest();
    AssetInfo assetInfo = Context.get().getAsset();
    boolean isQuotesRequired = assetInfo.getSep31().isQuotesRequired();
    boolean isQuotesSupported = assetInfo.getSep31().isQuotesSupported();

    // Check if a quote is provided.
    if (isQuotesRequired && request.getQuoteId() == null) {
      throw new BadRequestException("quotes_required is set to true; quote id cannot be empty");
    }

    if (!isQuotesSupported || request.getQuoteId() == null) {
      return;
    }

    Sep38Quote quote = sep38QuoteStore.findByQuoteId(request.getQuoteId());
    if (quote == null) {
      infoF("Quote ({}) was not found", request.getQuoteId());
      throw new BadRequestException(
          String.format("quote(id=%s) was not found.", request.getQuoteId()));
    }

    // Check quote amounts: `post_transaction.amount == quote.sell_amount`
    if (!amountEquals(request.getAmount(), quote.getSellAmount())) {
      infoF(
          "Quote ({}) - sellAmount ({}) is different from the SEP-31 transaction amount ({})",
          request.getQuoteId(),
          quote.getSellAmount(),
          request.getAmount());
      throw new BadRequestException(
          String.format(
              "Quote sell amount [%s] is different from the SEP-31 transaction amount [%s]",
              quote.getSellAmount(), request.getAmount()));
    }

    // Check quote asset: `post_transaction.asset == quote.sell_asset`
    String assetName = Context.get().getAsset().getId();
    if (!assetName.equals(quote.getSellAsset())) {
      infoF(
          "Quote ({}) - sellAsset ({}) is different from the SEP-31 transaction asset ({})",
          request.getQuoteId(),
          quote.getSellAsset(),
          assetName);
      throw new BadRequestException(
          String.format(
              "Quote sell asset [%s] is different from the SEP-31 transaction asset [%s]",
              quote.getSellAsset(), assetName));
    }

    Context.get().setQuote(quote);
  }

  /**
   * updateFee will update the transaction fee. If a quote was used, it will get the quote info and
   * use the quote fees for it, otherwise it will call `GET {callbackAPI}/fee` to get the fee
   * information
   *
   * @throws SepValidationException if the quote is missing the `fee` field.
   * @throws AnchorException if something else goes wrong.
   */
  void updateFee() throws SepValidationException, AnchorException {
    Sep38Quote quote = Context.get().getQuote();
    if (quote != null) {
      if (quote.getFee() == null) {
        infoF("Quote: ({}) is missing the 'fee' field", quote.getId());
        throw new SepValidationException("Quote is missing the 'fee' field");
      }
      Amount fee = new Amount(quote.getFee().getTotal(), quote.getFee().getAsset());
      Context.get().setFee(fee);
      return;
    }

    Sep31PostTransactionRequest request = Context.get().getRequest();
    String assetName = Context.get().getAsset().getId();
    infoF("Requesting fee for request ({})", request);
    var rate =
        rateIntegration
            .getRate(
                GetRateRequest.builder()
                    .type(GetRateRequest.Type.INDICATIVE)
                    .sellAmount(request.getAmount())
                    .sellAsset(assetName)
                    .buyAsset(
                        (request.getDestinationAsset() == null)
                            ? assetName
                            : request.getDestinationAsset())
                    .buyAmount(null)
                    .clientId(getClientName())
                    .build())
            .getRate();
    FeeDetails fee = rate.getFee();
    if (fee == null) {
      throw new SepValidationException("Fee is not present in /rate response");
    }
    infoF("Fee for request ({}) is ({})", request, fee);
    Amount amountFee = Amount.create(fee.getTotal(), fee.getAsset());
    Context.get().setFee(amountFee);
  }

  String getClientName() throws BadRequestException {
    return getClientName(Context.get().getWebAuthJwt().getAccount());
  }

  String getClientName(String account) throws BadRequestException {
    ClientConfig client = clientService.getClientConfigBySigningKey(account);
    if (sep10Config.isClientAttributionRequired() && client == null) {
      throw new BadRequestException("Client not found");
    }
    if (client != null && !sep10Config.getAllowedClientNames().contains(client.getName()))
      client = null;
    return client == null ? null : client.getName();
  }

  /**
   * validateRequiredFields will validate if the fields provided in the `POST /transactions` or
   * `PATCH /transactions/{id}` request body contains all the fields expected by the Anchor, and
   * pre-configured in the `app-config.app.assets`.
   *
   * @throws BadRequestException if the asset is invalid or id the fields are missing from the
   *     request
   * @throws Sep31MissingFieldException if not all fields were provided.
   */
  void validateRequiredFields() throws BadRequestException, Sep31MissingFieldException {
    AssetInfo assetInfo = Context.get().getAsset();
    if (assetInfo == null) {
      infoF("Missing asset information for request ({})", Context.get().getRequest());
      throw new BadRequestException("Missing asset information.");
    }

    AssetResponse fieldSpecs = this.infoResponse.getReceive().get(assetInfo.getCode());
    if (fieldSpecs == null) {
      infoF("Asset [{}] has no fields definition", Context.get().getRequest());
      throw new BadRequestException(
          String.format("Asset [%s] has no fields definition", assetInfo.getCode()));
    }

    Map<String, String> requestFields = Context.get().getTransactionFields();
    if (requestFields == null) {
      infoF(
          "'fields' field must have one 'transaction' field for request ({})",
          Context.get().getRequest());
      throw new BadRequestException("'fields' field must have one 'transaction' field");
    }
  }

  @SneakyThrows
  private static Sep31InfoResponse sep31InfoResponseFromAssetInfoList(List<AssetInfo> assetInfos) {
    Sep31InfoResponse response = new Sep31InfoResponse();
    response.setReceive(new HashMap<>());
    for (AssetInfo assetInfo : assetInfos) {
      if (assetInfo.getSep31() != null && assetInfo.getSep31().getEnabled()) {
        boolean isQuotesSupported = assetInfo.getSep31().isQuotesSupported();
        boolean isQuotesRequired = assetInfo.getSep31().isQuotesRequired();
        List<String> methods = assetInfo.getSep31().getReceive().getMethods();
        AssetResponse assetResponse = new AssetResponse();
        assetResponse.setQuotesSupported(isQuotesSupported);
        assetResponse.setQuotesRequired(isQuotesRequired);
        assetResponse.setMinAmount(assetInfo.getSep31().getReceive().getMinAmount());
        assetResponse.setMaxAmount(assetInfo.getSep31().getReceive().getMaxAmount());
        assetResponse.setFundingMethods(methods);
        response.getReceive().put(assetInfo.getCode(), assetResponse);
      }
    }
    return response;
  }

  @Data
  public static class Context {
    private Sep31Transaction transaction;
    private Sep31PostTransactionRequest request;
    private Sep38Quote quote;
    private WebAuthJwt webAuthJwt;
    private Amount fee;
    private AssetInfo asset;
    private Map<String, String> transactionFields;
    private static ThreadLocal<Context> context = new ThreadLocal<>();

    public static Context get() {
      if (context.get() == null) {
        context.set(new Context());
      }
      return context.get();
    }

    public static void reset() {
      context.set(null);
    }
  }
}
