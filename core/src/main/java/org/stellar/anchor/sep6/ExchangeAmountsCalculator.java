package org.stellar.anchor.sep6;

import static org.stellar.anchor.util.SepHelper.amountEquals;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteStore;

/** Calculates the amounts for an exchange request. */
@RequiredArgsConstructor
public class ExchangeAmountsCalculator {
  @NonNull private final Sep38QuoteStore sep38QuoteStore;

  /**
   * Calculates the amounts from a saved quote.
   *
   * @param quoteId The quote ID
   * @param sellAsset The asset the user is selling
   * @param sellAmount The amount the user is selling
   * @return The amounts
   * @throws AnchorException if the quote is invalid
   */
  public Amounts calculateFromQuote(String quoteId, AssetInfo sellAsset, String sellAmount)
      throws AnchorException {
    Sep38Quote quote = validateQuoteAgainstRequestInfo(quoteId, sellAsset, null, sellAmount);
    return Amounts.builder()
        .amountIn(quote.getSellAmount())
        .amountInAsset(quote.getSellAsset())
        .amountOut(quote.getBuyAmount())
        .amountOutAsset(quote.getBuyAsset())
        .feeDetails(quote.getFee())
        .build();
  }

  /**
   * validate the quote info against Sep6 or Sep24 or request info.
   *
   * @param quoteId The quote ID
   * @param sellAsset The asset the user is selling. It can be null for sep24 deposit request
   * @param buyAsset The amount the user is buying. It can be null for sep24 withdraw request
   * @param sellAmount The amount the user is selling. It can be null for sep24 deposit and withdraw
   *     requests
   * @return The quote
   * @throws AnchorException if the quote is invalid
   */
  public Sep38Quote validateQuoteAgainstRequestInfo(
      String quoteId, AssetInfo sellAsset, AssetInfo buyAsset, String sellAmount)
      throws AnchorException {
    Sep38Quote quote = sep38QuoteStore.findByQuoteId(quoteId);
    if (quote == null) {
      throw new BadRequestException("Quote not found");
    }

    if (sellAsset != null && !sellAsset.getSep38AssetName().equals(quote.getSellAsset())) {
      throw new BadRequestException(
          String.format(
              "source asset(%s) does not match quote sell asset(%s)",
              sellAsset.getSep38AssetName(), quote.getSellAsset()));
    }

    if (buyAsset != null && !buyAsset.getSep38AssetName().equals(quote.getBuyAsset())) {
      throw new BadRequestException(
          String.format(
              "destination asset(%s) does not match quote buy asset(%s)",
              buyAsset.getSep38AssetName(), quote.getBuyAsset()));
    }

    if (sellAmount != null && !amountEquals(sellAmount, quote.getSellAmount())) {
      throw new BadRequestException(
          String.format(
              "amount(%s) does not match quote sell amount(%s)",
              sellAmount, quote.getSellAmount()));
    }

    FeeDetails fee = quote.getFee();
    if (fee == null) {
      throw new SepValidationException("Quote is missing the 'fee' field");
    }

    return quote;
  }

  /** Amounts calculated for an exchange request. */
  @Builder
  @Data
  public static class Amounts {
    String amountIn;
    String amountInAsset;
    String amountOut;
    String amountOutAsset;
    FeeDetails feeDetails;
  }
}
