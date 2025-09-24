package org.stellar.anchor.sep38;

import static org.stellar.anchor.api.sep.sep38.Sep38Context.*;
import static org.stellar.anchor.api.sep.sep38.Sep38QuoteResponse.*;
import static org.stellar.anchor.event.EventService.EventQueue.TRANSACTION;
import static org.stellar.anchor.util.BeanHelper.updateField;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;
import static org.stellar.anchor.util.MetricConstants.SEP38_PRICE_QUERIED;
import static org.stellar.anchor.util.MetricConstants.SEP38_QUOTE_CREATED;
import static org.stellar.anchor.util.NumberHelper.DEFAULT_ROUNDING_MODE;
import static org.stellar.anchor.util.SepHelper.validateAmount;
import static org.stellar.anchor.util.SepHelper.validateAmountLimit;
import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.apache.commons.lang3.StringUtils;
import org.stellar.anchor.api.callback.*;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.api.platform.GetQuoteResponse;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.sep.sep38.*;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.Sep10Jwt;
import org.stellar.anchor.config.Sep38Config;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.util.Log;

public class Sep38Service {
  final AssetService assetService;
  final RateIntegration rateIntegration;
  final Sep38QuoteStore sep38QuoteStore;
  final EventService.Session eventSession;
  final InfoResponse infoResponse;
  final Map<String, InfoResponse.Asset> assetMap;
  final int pricePrecision = 10;
  final Counter sep38PriceQueried = Metrics.counter(SEP38_PRICE_QUERIED);
  final Counter sep38QuoteCreated = Metrics.counter(SEP38_QUOTE_CREATED);

  public Sep38Service(
      Sep38Config sep38Config,
      AssetService assetService,
      RateIntegration rateIntegration,
      Sep38QuoteStore sep38QuoteStore,
      EventService eventService) {
    debug("sep38Config:", sep38Config);
    this.assetService = assetService;
    this.rateIntegration = rateIntegration;
    this.sep38QuoteStore = sep38QuoteStore;
    this.eventSession = eventService.createSession(this.getClass().getName(), TRANSACTION);
    this.infoResponse = new InfoResponse(this.assetService.listAllAssets());
    assetMap = new HashMap<>();
    this.infoResponse.getAssets().forEach(asset -> assetMap.put(asset.getAsset(), asset));
    Log.info("Sep38Service initialized.");
  }

  public InfoResponse getInfo() {
    return this.infoResponse;
  }

  public GetPricesResponse getPrices(
      String sellAssetName,
      String sellAmount,
      String sellDeliveryMethod,
      String buyDeliveryMethod,
      String countryCode)
      throws AnchorException {
    if (this.rateIntegration == null) {
      throw new ServerErrorException("internal server error");
    }
    validateAsset("sell_", sellAssetName);
    validateAmount("sell_", sellAmount);

    InfoResponse.Asset sellAsset = assetMap.get(sellAssetName);

    // sellDeliveryMethod
    if (!Objects.toString(sellDeliveryMethod, "").isEmpty()) {
      if (!sellAsset.supportsSellDeliveryMethod(sellDeliveryMethod)) {
        throw new BadRequestException("Unsupported sell delivery method");
      }
    }

    // countryCode
    if (!Objects.toString(countryCode, "").isEmpty()) {
      if (sellAsset.getCountryCodes() == null
          || !sellAsset.getCountryCodes().contains(countryCode)) {
        throw new BadRequestException("Unsupported country code");
      }
    }

    // Make requests to `GET {quoteIntegration}/rates`
    GetRateRequest.GetRateRequestBuilder builder =
        GetRateRequest.builder()
            .type(GetRateRequest.Type.INDICATIVE)
            .sellAsset(sellAssetName)
            .sellAmount(sellAmount)
            .countryCode(countryCode)
            .sellDeliveryMethod(sellDeliveryMethod)
            .buyDeliveryMethod(buyDeliveryMethod);
    GetPricesResponse response = new GetPricesResponse();
    for (String buyAssetName : sellAsset.getExchangeableAssetNames()) {
      InfoResponse.Asset buyAsset = this.assetMap.get(buyAssetName);
      if (buyAsset == null || !buyAsset.supportsBuyDeliveryMethod(buyDeliveryMethod)) {
        continue;
      }

      GetRateRequest request = builder.buyAsset(buyAssetName).build();
      GetRateResponse rateResponse = this.rateIntegration.getRate(request);
      GetRateResponse.Rate rate = rateResponse.getRate();
      response.addAsset(buyAssetName, buyAsset.getDecimals(), rate.getPrice());
    }

    // increment counter
    sep38PriceQueried.increment();
    return response;
  }

  public void validateAsset(String prefix, String assetName) throws AnchorException {
    // assetName
    if (Objects.toString(assetName, "").isEmpty()) {
      throw new BadRequestException(prefix + "asset cannot be empty");
    }

    InfoResponse.Asset asset = assetMap.get(assetName);
    if (asset == null) {
      throw new NotFoundException(prefix + "asset not found");
    }
  }

  public GetPriceResponse getPrice(Sep10Jwt token, Sep38GetPriceRequest getPriceRequest)
      throws AnchorException {
    String sellAssetName = getPriceRequest.getSellAssetName();
    String sellAmount = getPriceRequest.getSellAmount();
    String sellDeliveryMethod = getPriceRequest.getSellDeliveryMethod();
    String buyAssetName = getPriceRequest.getBuyAssetName();
    String buyAmount = getPriceRequest.getBuyAmount();
    String buyDeliveryMethod = getPriceRequest.getBuyDeliveryMethod();
    String countryCode = getPriceRequest.getCountryCode();
    Sep38Context context = getPriceRequest.getContext();

    if (this.rateIntegration == null) {
      Log.error("rateIntegration should not be null!");
      throw new ServerErrorException("internal server error");
    }
    validateAsset("sell_", sellAssetName);
    validateAsset("buy_", buyAssetName);

    if ((sellAmount == null && buyAmount == null) || (sellAmount != null && buyAmount != null)) {
      throw new BadRequestException("Please provide either sell_amount or buy_amount");
    } else if (sellAmount != null) {
      validateAmount("sell_", sellAmount);
    } else {
      validateAmount("buy_", buyAmount);
    }

    InfoResponse.Asset sellAsset = assetMap.get(sellAssetName);
    InfoResponse.Asset buyAsset = assetMap.get(buyAssetName);

    // sellDeliveryMethod
    if (!Objects.toString(sellDeliveryMethod, "").isEmpty()) {
      if (!sellAsset.supportsSellDeliveryMethod(sellDeliveryMethod)) {
        throw new BadRequestException("Unsupported sell delivery method");
      }
    }

    // buyDeliveryMethod
    if (!Objects.toString(buyDeliveryMethod, "").isEmpty()) {
      if (!buyAsset.supportsBuyDeliveryMethod(buyDeliveryMethod)) {
        throw new BadRequestException("Unsupported buy delivery method");
      }
    }

    // countryCode
    if (!Objects.toString(countryCode, "").isEmpty()) {
      List<String> sellCountryCodes = sellAsset.getCountryCodes();
      List<String> buyCountryCodes = buyAsset.getCountryCodes();
      boolean bothCountryCodesAreNull = sellCountryCodes == null && buyCountryCodes == null;
      boolean countryCodeIsSupportedForSell =
          sellCountryCodes != null && sellCountryCodes.contains(countryCode);
      boolean countryCodeIsSupportedForBuy =
          buyCountryCodes != null && buyCountryCodes.contains(countryCode);
      if (bothCountryCodesAreNull
          || !(countryCodeIsSupportedForSell || countryCodeIsSupportedForBuy)) {
        throw new BadRequestException("Unsupported country code");
      }
    }

    // context
    if (context == null || !List.of(SEP6, SEP31).contains(context)) {
      throw new BadRequestException("Unsupported context. Should be one of [sep6, sep31].");
    }

    // Check SEP31 send limits
    if (context == SEP31) {
      if (sellAsset == null) {
        throw new BadRequestException("Unsupported sell asset.");
      }
      String[] assetCode = sellAsset.getAsset().split(":");
      AssetInfo asset = assetService.getAsset(assetCode[1]);
      Long sendMinLimit = asset.getSend().getMinAmount();
      Long sendMaxLimit = asset.getSend().getMaxAmount();

      // When sell_amount is specified
      if (sellAmount != null) {
        validateAmountLimit("sell_", sellAmount, sendMinLimit, sendMaxLimit);
      }
    }

    GetRateRequest.GetRateRequestBuilder rrBuilder =
        GetRateRequest.builder()
            .type(GetRateRequest.Type.INDICATIVE)
            .sellAsset(sellAssetName)
            .sellAmount(sellAmount)
            .sellDeliveryMethod(sellDeliveryMethod)
            .buyAsset(buyAssetName)
            .buyAmount(buyAmount)
            .buyDeliveryMethod(buyDeliveryMethod)
            .countryCode(countryCode);

    String clientId = getClientIdFromToken(token);
    if (clientId != null) {
      rrBuilder.clientId(clientId);
    }

    // Get the rate
    GetRateRequest request = rrBuilder.build();
    GetRateResponse rateResponse = this.rateIntegration.getRate(request);
    GetRateResponse.Rate rate = rateResponse.getRate();

    // Check SEP31 sell_amount from rate integration when buy_amount is specified
    if (context == SEP31 && isNotEmpty(buyAmount)) {
      String[] assetCode = sellAsset.getAsset().split(":");
      AssetInfo asset = assetService.getAsset(assetCode[1]);
      Long sendMinLimit = asset.getSend().getMinAmount();
      Long sendMaxLimit = asset.getSend().getMaxAmount();

      validateAmountLimit("sell_", rate.getSellAmount(), sendMinLimit, sendMaxLimit);
    }

    String totalPrice =
        getTotalPrice(
            decimal(rate.getSellAmount(), pricePrecision),
            decimal(rate.getBuyAmount(), pricePrecision));

    FeeDetails feeDetails =
        (rate.getFee() != null)
            ? rate.getFee()
            : new FeeDetails("0", sellAssetName, new ArrayList<>());

    return GetPriceResponse.builder()
        .price(rate.getPrice())
        .totalPrice(totalPrice)
        .fee(feeDetails)
        .sellAmount(rate.getSellAmount())
        .buyAmount(rate.getBuyAmount())
        .build();
  }

  public Sep38QuoteResponse postQuote(Sep10Jwt token, Sep38PostQuoteRequest request)
      throws AnchorException {
    if (this.rateIntegration == null) {
      throw new ServerErrorException("internal server error");
    }

    if (this.sep38QuoteStore == null) {
      throw new ServerErrorException("internal server error");
    }

    // validate token
    if (token == null) {
      throw new BadRequestException("missing sep10 jwt token");
    }
    String account, memo = null, memoType = null;
    if (!Objects.toString(token.getMuxedAccount(), "").isEmpty()) {
      account = token.getMuxedAccount();
    } else if (!Objects.toString(token.getAccount(), "").isEmpty()) {
      account = token.getAccount();
      if (token.getAccountMemo() != null) {
        memo = token.getAccountMemo();
        memoType = "id";
      }
    } else {
      throw new BadRequestException("sep10 token is malformed");
    }

    validateAsset("sell_", request.getSellAssetName());
    validateAsset("buy_", request.getBuyAssetName());

    if ((request.getSellAmount() == null && request.getBuyAmount() == null)
        || (request.getSellAmount() != null && request.getBuyAmount() != null)) {
      throw new BadRequestException("Please provide either sell_amount or buy_amount");
    } else if (request.getSellAmount() != null) {
      validateAmount("sell_", request.getSellAmount());
    } else {
      validateAmount("buy_", request.getBuyAmount());
    }

    InfoResponse.Asset sellAsset = assetMap.get(request.getSellAssetName());
    InfoResponse.Asset buyAsset = assetMap.get(request.getBuyAssetName());

    // sellDeliveryMethod
    if (!Objects.toString(request.getSellDeliveryMethod(), "").isEmpty()) {
      if (!sellAsset.supportsSellDeliveryMethod(request.getSellDeliveryMethod())) {
        throw new BadRequestException("Unsupported sell delivery method");
      }
    }

    // buyDeliveryMethod
    if (!Objects.toString(request.getBuyDeliveryMethod(), "").isEmpty()) {
      if (!buyAsset.supportsBuyDeliveryMethod(request.getBuyDeliveryMethod())) {
        throw new BadRequestException("Unsupported buy delivery method");
      }
    }

    // countryCode
    if (!Objects.toString(request.getCountryCode(), "").isEmpty()) {
      List<String> sellCountryCodes = sellAsset.getCountryCodes();
      List<String> buyCountryCodes = buyAsset.getCountryCodes();
      boolean bothCountryCodesAreNull = sellCountryCodes == null && buyCountryCodes == null;
      boolean countryCodeIsSupportedForSell =
          sellCountryCodes != null && sellCountryCodes.contains(request.getCountryCode());
      boolean countryCodeIsSupportedForBuy =
          buyCountryCodes != null && buyCountryCodes.contains(request.getCountryCode());
      if (bothCountryCodesAreNull
          || !(countryCodeIsSupportedForSell || countryCodeIsSupportedForBuy)) {
        throw new BadRequestException("Unsupported country code");
      }
    }

    // expireAfter
    if (!Objects.toString(request.getExpireAfter(), "").isEmpty()) {
      try {
        Instant.parse(request.getExpireAfter());
      } catch (Exception ex) {
        throw new BadRequestException("expire_after is invalid");
      }
    }

    // context
    Sep38Context context = request.getContext();
    if (context == null || !List.of(SEP6, SEP24, SEP31).contains(context)) {
      throw new BadRequestException("Unsupported context. Should be one of [sep6, sep24, sep31].");
    }

    // Check SEP31 send limits
    if (context == SEP31) {
      if (sellAsset == null) {
        throw new BadRequestException("Unsupported sell asset.");
      }
      String[] assetCode = sellAsset.getAsset().split(":");
      AssetInfo asset = assetService.getAsset(assetCode[1]);
      Long sendMinLimit = asset.getSend().getMinAmount();
      Long sendMaxLimit = asset.getSend().getMaxAmount();

      // When sell_amount is specified
      if (request.getSellAmount() != null) {
        validateAmountLimit("sell_", request.getSellAmount(), sendMinLimit, sendMaxLimit);
      }
    }

    GetRateRequest.GetRateRequestBuilder getRateRequestBuilder =
        GetRateRequest.builder()
            .type(GetRateRequest.Type.FIRM)
            .sellAsset(request.getSellAssetName())
            .sellAmount(request.getSellAmount())
            .sellDeliveryMethod(request.getSellDeliveryMethod())
            .buyAsset(request.getBuyAssetName())
            .buyAmount(request.getBuyAmount())
            .buyDeliveryMethod(request.getBuyDeliveryMethod())
            .countryCode(request.getCountryCode())
            .expireAfter(request.getExpireAfter());

    // Sets the clientId if the customer exists
    String clientId = getClientIdFromToken(token);
    if (clientId != null) {
      getRateRequestBuilder.clientId(clientId);
    }
    GetRateRequest getRateRequest = getRateRequestBuilder.build();

    // Get and set the rate
    GetRateResponse.Rate rate = this.rateIntegration.getRate(getRateRequest).getRate();
    if (rate.getBuyAmount() == null || rate.getSellAmount() == null) {
      throw new ServerErrorException(
          String.format(
              "Unable to calculate total_price with buy_amount: %s and sell_amount: %s",
              rate.getBuyAmount(), rate.getSellAmount()));
    }

    String totalPrice =
        getTotalPrice(
            decimal(rate.getSellAmount(), pricePrecision),
            decimal(rate.getBuyAmount(), pricePrecision));

    Sep38QuoteResponse.Sep38QuoteResponseBuilder responseBuilder =
        builder()
            .id(rate.getId())
            .expiresAt(rate.getExpiresAt())
            .price(rate.getPrice())
            .sellAsset(request.getSellAssetName())
            .buyAsset(request.getBuyAssetName())
            .fee(rate.getFee());

    String sellAmount = formatAmount(decimal(rate.getSellAmount()), sellAsset.getDecimals());
    String buyAmount = formatAmount(decimal(rate.getBuyAmount()), buyAsset.getDecimals());
    responseBuilder =
        responseBuilder.totalPrice(totalPrice).sellAmount(sellAmount).buyAmount(buyAmount);

    // Check SEP31 sell_amount from rate integration when buy_amount is specified
    if (context == SEP31 && isNotEmpty(buyAmount)) {
      String[] assetCode = sellAsset.getAsset().split(":");
      AssetInfo asset = assetService.getAsset(assetCode[1]);
      Long sendMinLimit = asset.getSend().getMinAmount();
      Long sendMaxLimit = asset.getSend().getMaxAmount();

      validateAmountLimit("sell_", sellAmount, sendMinLimit, sendMaxLimit);
    }

    // save firm quote in the local database
    Sep38Quote newQuote =
        new Sep38QuoteBuilder(this.sep38QuoteStore)
            .id(rate.getId())
            .expiresAt(rate.getExpiresAt())
            .price(rate.getPrice())
            .totalPrice(totalPrice)
            .sellAsset(request.getSellAssetName())
            .sellAmount(sellAmount)
            .sellDeliveryMethod(request.getSellDeliveryMethod())
            .buyAsset(request.getBuyAssetName())
            .buyAmount(buyAmount)
            .buyDeliveryMethod(request.getBuyDeliveryMethod())
            .createdAt(Instant.now())
            .creatorAccountId(account)
            .creatorMemo(memo)
            .creatorMemoType(memoType)
            .fee(rate.getFee())
            .build();

    // TODO: open the connection with DB and only commit/save after publishing the event:
    this.sep38QuoteStore.save(newQuote);

    AnchorEvent event =
        AnchorEvent.builder()
            .type(AnchorEvent.Type.QUOTE_CREATED)
            .id(UUID.randomUUID().toString())
            .sep("38")
            .quote(
                GetQuoteResponse.builder()
                    .creator(
                        StellarId.builder()
                            // TODO where to get StellarId.id?
                            .account(newQuote.getCreatorAccountId())
                            .build())
                    .build())
            .build();

    updateField(newQuote, "id", event, "quote.id");
    updateField(newQuote, "sellAsset", event, "quote.sellAsset");
    updateField(newQuote, "sellAmount", event, "quote.sellAmount");
    updateField(newQuote, "buyAsset", event, "quote.buyAsset");
    updateField(newQuote, "buyAmount", event, "quote.buyAmount");
    updateField(newQuote, "expiresAt", event, "quote.expiresAt");
    updateField(newQuote, "createdAt", event, "quote.createdAt");
    updateField(newQuote, "price", event, "quote.price");
    updateField(newQuote, "totalPrice", event, "quote.totalPrice");
    updateField(newQuote, "creatorAccountId", event, "quote.creator.account");
    updateField(newQuote, "transactionId", event, "quote.transactionId");
    updateField(rate, "fee", event, "quote.fee");

    // publish event
    eventSession.publish(event);

    // increment counter
    sep38QuoteCreated.increment();
    return responseBuilder.build();
  }

  public Sep38QuoteResponse getQuote(Sep10Jwt token, String quoteId) throws AnchorException {
    if (this.sep38QuoteStore == null) {
      throw new ServerErrorException("internal server error");
    }

    // validate token
    if (token == null) {
      throw new BadRequestException("missing sep10 jwt token");
    }
    String account, memo = null, memoType = null;
    if (!Objects.toString(token.getMuxedAccount(), "").isEmpty()) {
      account = token.getMuxedAccount();
    } else if (!Objects.toString(token.getAccount(), "").isEmpty()) {
      account = token.getAccount();
      if (token.getAccountMemo() != null) {
        memo = token.getAccountMemo();
        memoType = "id";
      }
    } else {
      throw new BadRequestException("sep10 token is malformed");
    }

    // empty quote id
    if (StringUtils.isEmpty(quoteId)) {
      throw new BadRequestException("quote id cannot be empty");
    }

    // validate consistency between quote and jwt token
    Sep38Quote quote = this.sep38QuoteStore.findByQuoteId(quoteId);
    if (quote == null
        || !StringUtils.equals(quote.getCreatorAccountId(), account)
        || !StringUtils.equals(memo, quote.getCreatorMemo())
        || !StringUtils.equals(memoType, quote.getCreatorMemoType())) {
      throw new NotFoundException("quote not found");
    }

    return builder()
        .id(quote.getId())
        .expiresAt(quote.getExpiresAt())
        .totalPrice(quote.getTotalPrice())
        .price(quote.getPrice())
        .sellAsset(quote.getSellAsset())
        .sellAmount(quote.getSellAmount())
        .buyAsset(quote.getBuyAsset())
        .buyAmount(quote.getBuyAmount())
        .fee(quote.getFee())
        .build();
  }

  private String getTotalPrice(BigDecimal bSellAmount, BigDecimal bBuyAmount) {
    // total_price = sell_amount / buy_amount
    BigDecimal bTotalPrice = bSellAmount.divide(bBuyAmount, pricePrecision, DEFAULT_ROUNDING_MODE);

    return formatAmount(bTotalPrice, pricePrecision);
  }

  private String getClientIdFromToken(Sep10Jwt token) {
    if (token == null) return null;
    return token.getAccount();
  }
}
