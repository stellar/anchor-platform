package org.stellar.anchor.util;

import static org.stellar.anchor.util.SepHelper.amountEquals;

import java.time.Clock;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.sep38.Sep38Quote;
import org.stellar.anchor.sep38.Sep38QuoteOwnership;
import org.stellar.anchor.sep38.Sep38QuoteStore;

/** Calculates the amounts for an exchange request. */
@RequiredArgsConstructor
public class ExchangeAmountsCalculator {
  @NonNull private final Sep38QuoteStore sep38QuoteStore;
  @NonNull private final Clock clock;

  /**
   * Calculates the amounts from a saved quote the caller created, also checking the request's
   * funding method against the quote's delivery method. Only SEP-6 passes delivery methods.
   *
   * @param quoteId The quote ID
   * @param caller The identity of the request's JWT; must be the quote's creator
   * @param sellAsset The asset the user is selling
   * @param buyAsset The asset the user is buying (the request's destination asset)
   * @param sellAmount The amount the user is selling
   * @param sellDeliveryMethod The funding method for a deposit-exchange request; null to skip
   * @param buyDeliveryMethod The funding method for a withdraw-exchange request; null to skip
   * @return The amounts
   * @throws AnchorException if the quote is invalid or was not created by the caller
   */
  public Amounts calculateFromQuote(
      String quoteId,
      WebAuthJwt caller,
      AssetInfo sellAsset,
      AssetInfo buyAsset,
      String sellAmount,
      String sellDeliveryMethod,
      String buyDeliveryMethod)
      throws AnchorException {
    Sep38Quote quote =
        validateQuoteAgainstRequestInfo(
            quoteId,
            caller,
            sellAsset,
            buyAsset,
            sellAmount,
            sellDeliveryMethod,
            buyDeliveryMethod);
    return amountsOf(quote);
  }

  /**
   * Validates a quote against a SEP-24 request. The quote must have been created by the caller; a
   * quote that does not exist and a quote that belongs to someone else get the same answer.
   *
   * @throws AnchorException if the quote is invalid or was not created by the caller
   */
  public Sep38Quote validateQuoteAgainstRequestInfo(
      String quoteId, WebAuthJwt caller, AssetInfo sellAsset, AssetInfo buyAsset, String sellAmount)
      throws AnchorException {
    return validateQuoteAgainstRequestInfo(
        quoteId, caller, sellAsset, buyAsset, sellAmount, null, null);
  }

  /**
   * Validates a quote against a SEP-6 or SEP-24 request, also checking the request's funding method
   * against the quote's delivery method. The quote must have been created by the caller.
   *
   * @throws AnchorException if the quote is invalid or was not created by the caller
   */
  public Sep38Quote validateQuoteAgainstRequestInfo(
      String quoteId,
      WebAuthJwt caller,
      AssetInfo sellAsset,
      AssetInfo buyAsset,
      String sellAmount,
      String sellDeliveryMethod,
      String buyDeliveryMethod)
      throws AnchorException {
    Sep38Quote quote = sep38QuoteStore.findByQuoteId(quoteId);
    if (quote == null || !Sep38QuoteOwnership.isCreatedBy(quote, caller)) {
      throw new BadRequestException("Quote not found");
    }
    return validateLoadedQuote(
        quote, quoteId, sellAsset, buyAsset, sellAmount, sellDeliveryMethod, buyDeliveryMethod);
  }

  private static Amounts amountsOf(Sep38Quote quote) {
    return Amounts.builder()
        .amountIn(quote.getSellAmount())
        .amountInAsset(quote.getSellAsset())
        .amountOut(quote.getBuyAmount())
        .amountOutAsset(quote.getBuyAsset())
        .feeDetails(quote.getFee())
        .build();
  }

  private Sep38Quote validateLoadedQuote(
      Sep38Quote quote,
      String quoteId,
      AssetInfo sellAsset,
      AssetInfo buyAsset,
      String sellAmount,
      String sellDeliveryMethod,
      String buyDeliveryMethod)
      throws AnchorException {
    if (quote.getTransactionId() != null) {
      throw new BadRequestException(String.format("quote(id=%s) has already been used", quoteId));
    }

    if (quote.getExpiresAt() != null && !quote.getExpiresAt().isAfter(clock.instant())) {
      throw new BadRequestException(
          String.format("quote(id=%s) has expired at %s", quoteId, quote.getExpiresAt()));
    }

    if (sellAsset != null && !sellAsset.getId().equals(quote.getSellAsset())) {
      throw new BadRequestException(
          String.format(
              "source asset(%s) does not match quote sell asset(%s)",
              sellAsset.getId(), quote.getSellAsset()));
    }

    if (buyAsset != null && !buyAsset.getId().equals(quote.getBuyAsset())) {
      throw new BadRequestException(
          String.format(
              "destination asset(%s) does not match quote buy asset(%s)",
              buyAsset.getId(), quote.getBuyAsset()));
    }

    if (sellAmount != null && !amountEquals(sellAmount, quote.getSellAmount())) {
      throw new BadRequestException(
          String.format(
              "amount(%s) does not match quote sell amount(%s)",
              sellAmount, quote.getSellAmount()));
    }

    if (sellDeliveryMethod != null
        && !StringHelper.isEmpty(quote.getSellDeliveryMethod())
        && !sellDeliveryMethod.equals(quote.getSellDeliveryMethod())) {
      throw new BadRequestException(
          String.format(
              "funding_method(%s) does not match quote sell delivery method(%s)",
              sellDeliveryMethod, quote.getSellDeliveryMethod()));
    }

    if (buyDeliveryMethod != null
        && !StringHelper.isEmpty(quote.getBuyDeliveryMethod())
        && !buyDeliveryMethod.equals(quote.getBuyDeliveryMethod())) {
      throw new BadRequestException(
          String.format(
              "funding_method(%s) does not match quote buy delivery method(%s)",
              buyDeliveryMethod, quote.getBuyDeliveryMethod()));
    }

    FeeDetails fee = quote.getFee();
    if (fee == null) {
      throw new SepValidationException("Quote is missing the 'fee' field");
    }

    return quote;
  }

  /**
   * Bind a quote to a transaction. Delegates to {@link Sep38QuoteStore#bindToTransaction}.
   *
   * @param quoteId The quote ID
   * @param transactionId The transaction ID to bind
   * @throws BadRequestException if the quote has already been bound to a transaction
   * @throws SepException if a storage error occurs
   */
  public void bindQuoteToTransaction(String quoteId, String transactionId)
      throws BadRequestException, SepException {
    if (!sep38QuoteStore.bindToTransaction(quoteId, transactionId)) {
      throw new BadRequestException(String.format("quote(id=%s) has already been used", quoteId));
    }
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
