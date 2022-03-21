package org.stellar.anchor.reference.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kotlin.Pair;
import org.springframework.stereotype.Service;
import org.stellar.anchor.exception.*;
import org.stellar.anchor.reference.model.Quote;
import org.stellar.anchor.reference.repo.QuoteRepo;
import org.stellar.platform.apis.callbacks.requests.GetRateRequest;
import org.stellar.platform.apis.callbacks.responses.GetRateResponse;

@Service
public class RateService {
  private final QuoteRepo quoteRepo;

  RateService(QuoteRepo quoteRepo) {
    this.quoteRepo = quoteRepo;
  }

  public GetRateResponse getRate(GetRateRequest request) throws AnchorException {
    if (request.getId() != null) {
      throw new ServerErrorException("getting quote by id is not implemented yet");
    }

    if (request.getType() == null) {
      throw new BadRequestException("type cannot be empty");
    }

    if (request.getSellAsset() == null) {
      throw new BadRequestException("sell_asset cannot be empty");
    }

    if (request.getBuyAsset() == null) {
      throw new BadRequestException("buy_asset cannot be empty");
    }

    String sellAmount = request.getSellAmount();
    String buyAmount = request.getBuyAmount();
    if ((sellAmount == null && buyAmount == null) || (sellAmount != null && buyAmount != null)) {
      throw new BadRequestException("Please provide either sell_amount or buy_amount");
    } else if (sellAmount != null) {
      validateAmount("sell_", sellAmount);
    } else {
      validateAmount("buy_", buyAmount);
    }

    String price = ConversionPrice.getPrice(request.getSellAsset(), request.getBuyAsset());
    if (price == null) {
      throw new UnprocessableEntityException("the price for the given pair could not be found");
    }

    if (request.getType().equals("indicative")) {
      return new GetRateResponse(price);
    } else if (request.getType().equals("firm")) {
      Quote newQuote = createQuote(request, price);
      return new GetRateResponse(newQuote.getId(), newQuote.getPrice(), newQuote.getExpiresAt());
    }
    throw new BadRequestException("type is not supported");
  }

  private Quote createQuote(GetRateRequest request, String price) {
    Quote quote = new Quote();
    quote.setId(UUID.randomUUID().toString());
    quote.setSellAsset(request.getSellAsset());
    quote.setSellAmount(request.getSellAmount());
    quote.setSellDeliveryMethod(request.getSellDeliveryMethod());
    quote.setBuyAsset(request.getBuyAsset());
    quote.setBuyAmount(request.getBuyAmount());
    quote.setSellDeliveryMethod(request.getSellDeliveryMethod());
    quote.setCountryCode(request.getCountryCode());
    quote.setCreatedAt(LocalDateTime.now());
    quote.setPrice(price);
    quote.setStellarAccount(request.getAccount());
    quote.setMemo(request.getMemo());
    quote.setMemoType(request.getMemoType());

    // "calculate" expiresAt
    LocalDateTime expiresAfter = request.getExpiresAfter();
    if (expiresAfter == null) {
      expiresAfter = LocalDateTime.now();
    }
    LocalDateTime expiresAt = expiresAfter.withHour(12);
    expiresAt = expiresAt.withMinute(0);
    expiresAt = expiresAt.withSecond(0);
    expiresAt = expiresAt.withNano(0);
    expiresAt = expiresAt.plusDays(1);
    quote.setExpiresAt(expiresAt);

    quoteRepo.save(quote);
    return quote;
  }

  private static class ConversionPrice {
    private static final String fiatUSD = "iso4217:USD";
    private static final String stellarUSDC =
        "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5";
    private static final String stellarJPYC =
        "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5";
    private static final Map<Pair<String, String>, String> hardcodedPrices =
        Map.of(
            new Pair<>(fiatUSD, stellarUSDC), "1.02",
            new Pair<>(stellarUSDC, fiatUSD), "1.05",
            new Pair<>(fiatUSD, stellarJPYC), "0.0083333",
            new Pair<>(stellarJPYC, fiatUSD), "122",
            new Pair<>(stellarUSDC, stellarJPYC), "0.0084",
            new Pair<>(stellarJPYC, stellarUSDC), "120");

    public static String getPrice(String sellAsset, String buyAsset) {
      return hardcodedPrices.get(new Pair<>(sellAsset, buyAsset));
    }
  }

  private void validateAmount(String prefix, String amount) throws AnchorException {
    // assetName
    if (Objects.toString(amount, "").isEmpty()) {
      throw new BadRequestException(prefix + "amount cannot be empty");
    }

    BigDecimal sAmount;
    try {
      sAmount = new BigDecimal(amount);
    } catch (NumberFormatException e) {
      throw new BadRequestException(prefix + "amount is invalid", e);
    }
    if (sAmount.signum() < 1) {
      throw new BadRequestException(prefix + "amount should be positive");
    }
  }
}
