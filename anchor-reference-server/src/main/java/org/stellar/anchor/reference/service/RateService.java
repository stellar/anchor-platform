package org.stellar.anchor.reference.service;

import static org.stellar.anchor.api.callback.GetRateRequest.Type.FIRM;
import static org.stellar.anchor.api.callback.GetRateRequest.Type.INDICATIVE;
import static org.stellar.anchor.util.SepHelper.validateAmount;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kotlin.Pair;
import org.springframework.stereotype.Service;
import org.stellar.anchor.api.callback.GetRateRequest;
import org.stellar.anchor.api.callback.GetRateResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.exception.UnprocessableEntityException;
import org.stellar.anchor.api.sep.sep38.PriceDetail;
import org.stellar.anchor.reference.model.Quote;
import org.stellar.anchor.reference.repo.QuoteRepo;
import org.stellar.anchor.util.DateUtil;

@Service
public class RateService {
  private final QuoteRepo quoteRepo;

  RateService(QuoteRepo quoteRepo) {
    this.quoteRepo = quoteRepo;
  }

  public GetRateResponse getRate(GetRateRequest request) throws AnchorException {
    if (request.getId() != null) {
      Quote quote = quoteRepo.findById(request.getId()).orElse(null);
      if (quote == null) {
        throw new NotFoundException("Quote not found.");
      }
      return GetRateResponse.firm(
          quote.getId(), quote.getPrice(), quote.getExpiresAt(), quote.getPriceDetails());
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
    List<PriceDetail> priceDetails =
        ConversionPrice.getPriceDetails(request.getSellAsset(), request.getBuyAsset());

    if (request.getType() == INDICATIVE) {
      return GetRateResponse.indicative(price, priceDetails);
    } else if (request.getType() == FIRM) {
      Quote quote = createQuote(request, price, priceDetails);
      return GetRateResponse.firm(
          quote.getId(), quote.getPrice(), quote.getExpiresAt(), quote.getPriceDetails());
    }
    throw new BadRequestException("type is not supported");
  }

  private Quote createQuote(GetRateRequest request, String price, List<PriceDetail> priceDetails) {
    Quote quote = Quote.of(request);
    quote.setPrice(price);
    quote.setPriceDetails(priceDetails);

    // "calculate" expiresAt
    String strExpiresAfter = request.getExpireAfter();
    Instant expiresAfter;
    if (strExpiresAfter == null) {
      expiresAfter = Instant.now();
    } else {
      expiresAfter = Instant.ofEpochSecond(DateUtil.fromISO8601UTC(strExpiresAfter));
    }
    ZonedDateTime expiresAt =
        ZonedDateTime.ofInstant(expiresAfter, ZoneId.of("UTC"))
            .plusDays(1)
            .withHour(12)
            .withMinute(0)
            .withSecond(0)
            .withNano(0);
    quote.setExpiresAt(expiresAt.toInstant());

    quoteRepo.save(quote);
    return quote;
  }

  private static class ConversionPrice {
    private static final String fiatUSD = "iso4217:USD";
    private static final String stellarUSDCtest =
        "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5";
    private static final String stellarUSDCprod =
        "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN";
    private static final String stellarJPYC =
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP";
    private static final Map<Pair<String, String>, String> hardcodedPrices =
        Map.of(
            new Pair<>(fiatUSD, stellarUSDCtest), "1.02",
            new Pair<>(stellarUSDCtest, fiatUSD), "1.05",
            new Pair<>(fiatUSD, stellarUSDCprod), "1.02",
            new Pair<>(stellarUSDCprod, fiatUSD), "1.05",
            new Pair<>(fiatUSD, stellarJPYC), "0.0083333",
            new Pair<>(stellarJPYC, fiatUSD), "122",
            new Pair<>(stellarUSDCtest, stellarJPYC), "0.0084",
            new Pair<>(stellarJPYC, stellarUSDCtest), "120",
            new Pair<>(stellarUSDCprod, stellarJPYC), "0.0084",
            new Pair<>(stellarJPYC, stellarUSDCprod), "120");

    public static String getPrice(String sellAsset, String buyAsset) {
      return hardcodedPrices.get(new Pair<>(sellAsset, buyAsset));
    }

    public static List<PriceDetail> getPriceDetails(String sellAsset, String buyAsset) {
      List<PriceDetail> priceDetails = new ArrayList<>();
      if (getPrice(sellAsset, buyAsset) != null) {
        PriceDetail sellAssetPriceDetail = new PriceDetail("Sell fee", sellAsset, "1.00");
        priceDetails.add(sellAssetPriceDetail);

        PriceDetail buyAssetPriceDetail = new PriceDetail("Buy fee", buyAsset, "0.99");
        priceDetails.add(buyAssetPriceDetail);
      }
      return priceDetails;
    }
  }
}
