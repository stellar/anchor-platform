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
import static org.stellar.anchor.util.SepHelper.amountEquals;
import static org.stellar.anchor.util.SepHelper.generateSepTransactionId;
import static org.stellar.anchor.util.SepHelper.validateAmount;
import static org.stellar.anchor.util.SepHelper.validateAmountLimit;
import static org.stellar.anchor.util.SepLanguageHelper.validateLanguage;
import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.sdk.xdr.MemoType.MEMO_NONE;

import io.micrometer.core.instrument.Counter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import javax.transaction.Transactional;
import lombok.Data;
import lombok.SneakyThrows;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.FeeIntegration;
import org.stellar.anchor.api.callback.GetCustomerRequest;
import org.stellar.anchor.api.callback.GetCustomerResponse;
import org.stellar.anchor.api.callback.GetFeeRequest;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.exception.Sep31CustomerInfoNeededException;
import org.stellar.anchor.api.exception.Sep31MissingFieldException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.sep.operation.Sep12Operation;
import org.stellar.anchor.api.sep.operation.Sep31Operation.Fields;
import org.stellar.anchor.api.sep.sep12.Sep12Status;
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse;
import org.stellar.anchor.api.sep.sep31.Sep31InfoResponse;
import org.stellar.anchor.api.sep.sep31.Sep31PatchTransactionRequest;
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest;
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.SepDepositInfo;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.ClientsConfig;
import org.stellar.anchor.config.ClientsConfig.ClientConfig;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.config.Sep10Config;
import org.stellar.anchor.config.Sep31Config;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteStore;
import org.stellar.anchor.util.CustodyUtils;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.TransactionHelper;

public class Sep31Service {
  private final AppConfig appConfig;
  private final Sep10Config sep10Config;
  private final Sep31Config sep31Config;
  private final Sep31TransactionStore sep31TransactionStore;
  private final Sep31DepositInfoGenerator sep31DepositInfoGenerator;
  private final Sep38QuoteStore sep38QuoteStore;
  private final ClientsConfig clientsConfig;
  private final AssetService assetService;
  private final FeeIntegration feeIntegration;
  private final CustomerIntegration customerIntegration;
  private final Sep31InfoResponse infoResponse;
  private final CustodyService custodyService;
  private final CustodyConfig custodyConfig;
  private final EventService.Session eventSession;
  private final Counter sep31TransactionCreatedCounter = counter(SEP31_TRANSACTION_CREATED);
  private final Counter sep31TransactionPatchedCounter = counter(SEP31_TRANSACTION_PATCHED);

  public Sep31Service(
      AppConfig appConfig,
      Sep10Config sep10Config,
      Sep31Config sep31Config,
      Sep31TransactionStore sep31TransactionStore,
      Sep31DepositInfoGenerator sep31DepositInfoGenerator,
      Sep38QuoteStore sep38QuoteStore,
      ClientsConfig clientsConfig,
      AssetService assetService,
      FeeIntegration feeIntegration,
      CustomerIntegration customerIntegration,
      EventService eventService,
      CustodyService custodyService,
      CustodyConfig custodyConfig) {
    debug("appConfig:", appConfig);
    debug("sep31Config:", sep31Config);
    this.appConfig = appConfig;
    this.sep10Config = sep10Config;
    this.sep31Config = sep31Config;
    this.sep31TransactionStore = sep31TransactionStore;
    this.sep31DepositInfoGenerator = sep31DepositInfoGenerator;
    this.sep38QuoteStore = sep38QuoteStore;
    this.clientsConfig = clientsConfig;
    this.assetService = assetService;
    this.feeIntegration = feeIntegration;
    this.customerIntegration = customerIntegration;
    this.eventSession = eventService.createSession(this.getClass().getName(), TRANSACTION);
    this.infoResponse = sep31InfoResponseFromAssetInfoList(assetService.listAllAssets());
    this.custodyService = custodyService;
    this.custodyConfig = custodyConfig;
    Log.info("Sep31Service initialized.");
  }

  public Sep31InfoResponse getInfo() {
    return infoResponse;
  }

  @Transactional(rollbackOn = {AnchorException.class, RuntimeException.class})
  public Sep31PostTransactionResponse postTransaction(
      Sep10Jwt sep10Jwt, Sep31PostTransactionRequest request) throws AnchorException {
    Context.reset();
    Context.get().setRequest(request);
    Context.get().setSep10Jwt(sep10Jwt);

    AssetInfo assetInfo = assetService.getAsset(request.getAssetCode(), request.getAssetIssuer());
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
        assetInfo.getSend().getMinAmount(),
        assetInfo.getSend().getMaxAmount());
    validateLanguage(appConfig, request.getLang());

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
          "POST /transaction with id ({}) cannot have empty `fields`", sep10Jwt.getTransactionId());
      throw new BadRequestException("'fields' field cannot be empty");
    }
    Context.get().setTransactionFields(request.getFields().getTransaction());
    validateRequiredFields();

    // Validation that execute HTTP requests
    validateSenderAndReceiver();
    preValidateQuote();

    // Query the fee
    updateFee();

    // Get the creator's stellarId
    StellarId creatorStellarId =
        StellarId.builder()
            .account(Objects.requireNonNullElse(sep10Jwt.getMuxedAccount(), sep10Jwt.getAccount()))
            .memo(sep10Jwt.getAccountMemo())
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
            .status(SepTransactionStatus.PENDING_SENDER.getStatus())
            .statusEta(null)
            .feeDetails(feeDetails)
            .startedAt(now)
            .updatedAt(now) // this will be overwritten by the sep31TransactionStore#save method.
            .completedAt(null)
            .stellarTransactionId(null)
            .externalTransactionId(null)
            .requiredInfoMessage(null)
            .quoteId(request.getQuoteId())
            .clientDomain(sep10Jwt.getClientDomain())
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
            .amountInAsset(assetInfo.getSep38AssetName())
            .amountOut(null)
            .amountOutAsset(null)
            .stellarAccountId(assetInfo.getDistributionAccount())
            .stellarMemo(null)
            .stellarMemoType(null)
            .build();

    // updateDepositInfo will update these ⬇️
    if (!isEmpty(assetInfo.getDistributionAccount())) {
      txn.setStellarAccountId(assetInfo.getDistributionAccount());
    }

    Context.get().setTransaction(txn);
    updateAmounts();

    // TODO: open the connection with DB and only commit/save after publishing the event:
    Context.get().setTransaction(sep31TransactionStore.save(txn));
    txn = Context.get().getTransaction();

    updateDepositInfo();

    if (custodyConfig.isCustodyIntegrationEnabled()) {
      custodyService.createTransaction(txn);
    }

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep("31")
            .type(TRANSACTION_CREATED)
            .transaction(TransactionHelper.toGetTransactionResponse(txn))
            .build());

    Sep31PostTransactionResponse response =
        Sep31PostTransactionResponse.builder()
            .id(txn.getId())
            .stellarAccountId(txn.getStellarAccountId())
            .stellarMemo(isEmpty(txn.getStellarMemo()) ? "" : txn.getStellarMemo())
            .stellarMemoType(
                isEmpty(txn.getStellarMemoType()) ? MEMO_NONE.name() : txn.getStellarMemoType())
            .build();
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
    txn.setAmountFee(quote.getFee().getTotal());
    txn.setAmountFeeAsset(quote.getFee().getAsset());
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

    String amountInAsset = reqAsset.getSep38AssetName();
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
    txn.setAmountFee(feeStr);
    txn.setAmountFeeAsset(feeResponse.getAsset());
    Context.get().getFee().setAmount(feeStr);
  }

  /**
   * updateDepositInfo will populate the transaction's deposit information (stellar_account_id, memo
   * and memo_type), as provided by the sep31DepositInfoGenerator.
   */
  void updateDepositInfo() throws AnchorException {
    Sep31Transaction txn = Context.get().getTransaction();
    SepDepositInfo depositInfo = sep31DepositInfoGenerator.generate(txn);
    infoF("Updating transaction ({}) with depositInfo ({})", txn.getId(), depositInfo);

    if (!CustodyUtils.isMemoTypeSupported(custodyConfig.getType(), depositInfo.getMemoType())) {
      throw new BadRequestException(
          String.format(
              "Memo type[%s] is not supported for custody type[%s]",
              depositInfo.getMemoType(), custodyConfig.getType()));
    }

    txn.setStellarAccountId(depositInfo.getStellarAddress());
    txn.setStellarMemo(depositInfo.getMemo());
    txn.setStellarMemoType(isEmpty(depositInfo.getMemoType()) ? "none" : depositInfo.getMemoType());
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

    if (isQuotesRequired && request.getQuoteId() == null) {
      throw new BadRequestException("quotes_required is set to true; quote id cannot be empty");
    }

    // Check if quote is provided.
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
    String assetName = Context.get().getAsset().getSep38AssetName();
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
    Sep10Jwt token = Context.get().getSep10Jwt();
    String assetName = Context.get().getAsset().getSep38AssetName();
    infoF("Requesting fee for request ({})", request);
    Amount fee =
        feeIntegration
            .getFee(
                GetFeeRequest.builder()
                    .sendAmount(request.getAmount())
                    .sendAsset(assetName)
                    .receiveAsset(
                        (request.getDestinationAsset() == null)
                            ? assetName
                            : request.getDestinationAsset())
                    .receiveAmount(null)
                    .senderId(request.getSenderId())
                    .receiverId(request.getReceiverId())
                    .clientId(getClientName())
                    .build())
            .getFee();
    infoF("Fee for request ({}) is ({})", request, fee);
    Context.get().setFee(fee);
  }

  String getClientName() throws BadRequestException {
    return getClientName(Context.get().getSep10Jwt().getAccount());
  }

  String getClientName(String account) throws BadRequestException {
    ClientConfig client = clientsConfig.getClientConfigBySigningKey(account);
    if (sep10Config.isClientAttributionRequired() && client == null) {
      throw new BadRequestException("Client not found");
    }
    if (client != null && !sep10Config.getAllowedClientNames().contains(client.getName()))
      client = null;
    return client == null ? null : client.getName();
  }

  /**
   * validateSenderAndReceiver will validate if the SEP-31 sender and receiver exist and their
   * status is ACCEPTED.
   *
   * @throws BadRequestException if `sender_id` or `receiver_id` is empty.
   * @throws Sep31CustomerInfoNeededException if the SEP-12 customer does not exist or if its status
   *     is not ACCEPTED.
   * @throws AnchorException is something else went wrong.
   */
  private void validateSenderAndReceiver()
      throws AnchorException, BadRequestException, Sep31CustomerInfoNeededException {
    String receiverId = Context.get().getRequest().getReceiverId();
    if (receiverId == null) {
      infoF("'receiver_id' cannot be empty for request ({})", Context.get().getRequest());
      throw new BadRequestException("receiver_id cannot be empty.");
    }

    // TODO: Populate customer type with the first receiver type; this is a temporary fix for cases
    // where customerType is required
    Sep12Operation sep12Operation = Context.get().getAsset().getSep31().getSep12();
    String receiverType = null;
    if (sep12Operation != null) {
      Optional<String> receiverTypeOptional =
          sep12Operation.getReceiver().getTypes().keySet().stream().findFirst();
      receiverType = receiverTypeOptional.orElse(null);
    }
    GetCustomerRequest request =
        GetCustomerRequest.builder().id(receiverId).type(receiverType).build();
    GetCustomerResponse receiver = this.customerIntegration.getCustomer(request);
    if (receiver == null || !Objects.equals(receiver.getStatus(), Sep12Status.ACCEPTED.name())) {
      infoF("Customer (receiver) info needed for request ({})", Context.get().getRequest());
      throw new Sep31CustomerInfoNeededException("sep31-receiver");
    }

    String senderId = Context.get().getRequest().getSenderId();
    if (senderId == null) {
      infoF("'sender_id' cannot be empty for request ({})", Context.get().getRequest());
      throw new BadRequestException("sender_id cannot be empty.");
    }

    // TODO: Populate customer type with the first sender type; this is a temporary fix for cases
    // where customerType is required
    String senderType = null;
    if (sep12Operation != null) {
      Optional<String> senderTypeOptional =
          Context.get().getAsset().getSep31().getSep12().getSender().getTypes().keySet().stream()
              .findFirst();
      senderType = senderTypeOptional.orElse(null);
    }
    request = GetCustomerRequest.builder().id(senderId).type(senderType).build();
    GetCustomerResponse sender = this.customerIntegration.getCustomer(request);
    if (sender == null || !Objects.equals(sender.getStatus(), Sep12Status.ACCEPTED.name())) {
      infoF("Customer (sender) info needed for request ({})", Context.get().getRequest());
      throw new Sep31CustomerInfoNeededException("sep31-sender");
    }
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

    Map<String, AssetInfo.Field> missingFields =
        fieldSpecs.getFields().getTransaction().entrySet().stream()
            .filter(
                entry -> {
                  AssetInfo.Field field = entry.getValue();
                  if (field.isOptional()) return false;
                  return requestFields.get(entry.getKey()) == null;
                })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Fields sep31MissingTxnFields = new Fields();
    sep31MissingTxnFields.setTransaction(missingFields);

    if (missingFields.size() > 0) {
      infoF(
          "Missing SEP-31 fields ({}) for request ({})",
          sep31MissingTxnFields,
          Context.get().getRequest());
      throw new Sep31MissingFieldException(sep31MissingTxnFields);
    }
  }

  @SneakyThrows
  private static Sep31InfoResponse sep31InfoResponseFromAssetInfoList(List<AssetInfo> assetInfos) {
    Sep31InfoResponse response = new Sep31InfoResponse();
    response.setReceive(new HashMap<>());
    for (AssetInfo assetInfo : assetInfos) {
      if (assetInfo.getSep31Enabled()) {
        boolean isQuotesSupported = assetInfo.getSep31().isQuotesSupported();
        boolean isQuotesRequired = assetInfo.getSep31().isQuotesRequired();
        if (isQuotesRequired && !isQuotesSupported) {
          throw new SepValidationException(
              "if quotes_required is true, quotes_supported must also be true");
        }
        AssetResponse assetResponse = new AssetResponse();
        assetResponse.setQuotesSupported(isQuotesSupported);
        assetResponse.setQuotesRequired(isQuotesRequired);
        assetResponse.setFeeFixed(assetInfo.getSend().getFeeFixed());
        assetResponse.setFeePercent(assetInfo.getSend().getFeePercent());
        assetResponse.setMinAmount(assetInfo.getSend().getMinAmount());
        assetResponse.setMaxAmount(assetInfo.getSend().getMaxAmount());
        assetResponse.setFields(assetInfo.getSep31().getFields());
        assetResponse.setSep12(assetInfo.getSep31().getSep12());
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
    private Sep10Jwt sep10Jwt;
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
