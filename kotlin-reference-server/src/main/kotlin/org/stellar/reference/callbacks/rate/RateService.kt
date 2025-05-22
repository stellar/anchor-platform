package org.stellar.reference.callbacks.rate

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import org.stellar.anchor.api.callback.GetRateRequest
import org.stellar.anchor.api.callback.GetRateResponse
import org.stellar.anchor.api.shared.FeeDescription
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.reference.callbacks.BadRequestException
import org.stellar.reference.callbacks.NotFoundException
import org.stellar.reference.dao.QuoteRepository
import org.stellar.reference.model.Quote

class RateService(private val quoteRepository: QuoteRepository) {
  private val scale = 4
  fun getRate(request: GetRateRequest): GetRateResponse {
    validateRequest(request)
    val price =
      getPrice(request.sellAsset!!, request.buyAsset!!)?.let { getDecimal(it, scale) }
        ?: throw RuntimeException(
          "Price not found for ${request.sellAsset} and ${request.buyAsset}"
        )
    val buyAmount = request.buyAmount?.let { getDecimal(it, scale) }
    val sellAmount = request.sellAmount?.let { getDecimal(it, scale) }
    val fee = getFee(request.sellAsset!!, request.buyAsset!!)
    val feeAmount = fee.total.toBigDecimal()

    val finalBuyAmount =
      sellAmount?.subtract(feeAmount)?.divide(price, RoundingMode.HALF_DOWN)?.also {
        if (it <= BigDecimal.ZERO) {
          throw RuntimeException("Buy amount must be greater than zero")
        }
      }
        ?: buyAmount
    val finalSellAmount =
      buyAmount?.setScale(10, RoundingMode.HALF_DOWN)?.multiply(price)?.add(feeAmount) ?: sellAmount
    val finalPrice =
      finalSellAmount
        ?.setScale(10, RoundingMode.HALF_DOWN)
        ?.subtract(feeAmount)
        ?.divide(finalBuyAmount, RoundingMode.HALF_DOWN)
        ?: price
    val finalTotalPrice = finalSellAmount?.divide(finalBuyAmount, 10, RoundingMode.HALF_DOWN)

    if (request.type == GetRateRequest.Type.INDICATIVE) {
      return GetRateResponse.indicativePrice(
        getString(finalPrice, 10),
        getString(finalSellAmount!!, scale),
        getString(finalBuyAmount!!, scale),
        fee
      )
    }

    val expiresAfter =
      request.expireAfter?.let { ZonedDateTime.parse(it).toInstant() } ?: Instant.now()
    val expiresAt =
      ZonedDateTime.ofInstant(expiresAfter, ZoneId.of("UTC"))
        .plusDays(1)
        .withHour(12)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
        .toInstant()
    val quote =
      Quote(
        id = UUID.randomUUID().toString(),
        sellAsset = request.sellAsset,
        sellAmount = getString(finalSellAmount!!, scale),
        sellDeliveryMethod = request.sellDeliveryMethod,
        buyAsset = request.buyAsset,
        buyAmount = getString(finalBuyAmount!!, scale),
        buyDeliveryMethod = request.buyDeliveryMethod,
        countryCode = request.countryCode,
        createdAt = Instant.now(),
        expiresAt = expiresAt,
        clientId = request.clientId,
        price = getString(finalPrice, 10),
        totalPrice = getString(finalTotalPrice!!, 10),
        fee =
          org.stellar.reference.model.FeeDetails(
            fee.total,
            fee.asset,
            fee.details.map {
              org.stellar.reference.model.FeeDescription(it.name, it.description, it.amount)
            }
          )
      )
    quoteRepository.create(quote)

    val rate =
      GetRateResponse.Rate.builder()
        .id(quote.id)
        .price(quote.price)
        .sellAmount(quote.sellAmount)
        .buyAmount(quote.buyAmount)
        .expiresAt(quote.expiresAt)
        .fee(fee)
        .build()
    return GetRateResponse(rate)
  }

  private fun getRate(id: String): GetRateResponse.Rate {
    val quote =
      quoteRepository.get(id) ?: throw NotFoundException("Rate with quote id $id not found", id)
    val feeDetails = FeeDetails("0", quote.fee?.asset)
    quote.fee
      ?.details
      ?.forEach(
        fun(detail: org.stellar.reference.model.FeeDescription) {
          feeDetails.addFeeDetail(FeeDescription(detail.name, detail.description, detail.amount))
        }
      )

    return GetRateResponse.Rate.builder()
      .id(quote.id)
      .price(quote.price)
      .sellAmount(quote.sellAmount)
      .buyAmount(quote.buyAmount)
      .expiresAt(quote.expiresAt)
      .fee(feeDetails)
      .build()
  }

  private fun validateRequest(request: GetRateRequest) {
    if (request.type == null) {
      throw BadRequestException("Type must be provided")
    }

    if (!listOf(GetRateRequest.Type.INDICATIVE, GetRateRequest.Type.FIRM).contains(request.type)) {
      throw BadRequestException("Type must be either indicative or firm")
    }

    if (request.sellAsset == null) {
      throw BadRequestException("Sell asset must be provided")
    }

    if (request.buyAsset == null) {
      throw BadRequestException("Buy asset must be provided")
    }

    if (
      (request.sellAmount == null && request.buyAmount == null) ||
        (request.sellAmount != null && request.buyAmount != null)
    ) {
      throw BadRequestException("Either sell amount or buy amount must be provided but not both")
    }
  }

  private fun getString(amount: BigDecimal, scale: Int): String {
    val newAmount = amount.setScale(scale, RoundingMode.DOWN)

    val df = DecimalFormat()
    df.maximumFractionDigits = scale
    df.minimumFractionDigits = 0
    df.isGroupingUsed = false

    return df.format(newAmount)
  }

  private fun getDecimal(amount: String, scale: Int): BigDecimal {
    if (amount.isBlank()) {
      throw RuntimeException("Amount must be provided")
    }
    val result = amount.toBigDecimal().setScale(scale, RoundingMode.DOWN)
    if (result <= BigDecimal.ZERO) {
      throw RuntimeException("Amount must be greater than zero")
    }

    return result
  }

  companion object {
    val fiatUSD = "iso4217:USD"
    val fiatCAD = "iso4217:CAD"
    val stellarCircleUSDCtest =
      "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    val stellarUSDCtest = "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val stellarUSDCprod = "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    val stellarJPYC = "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val stellarSRT = "stellar:SRT:GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B"
    val prices =
      mapOf(
        Pair(fiatUSD, stellarCircleUSDCtest) to "1.02",
        Pair(fiatUSD, stellarUSDCtest) to "1.02",
        Pair(fiatUSD, stellarJPYC) to "0.0083333",
        Pair(fiatUSD, stellarSRT) to "0.145",
        Pair(fiatCAD, stellarUSDCtest) to "0.74",
        Pair(fiatCAD, stellarUSDCprod) to "0.74",
        Pair(fiatCAD, stellarSRT) to "0.406",
        Pair(stellarUSDCtest, fiatUSD) to "1.05",
        Pair(stellarUSDCtest, fiatUSD) to "1.05",
        Pair(stellarUSDCtest, stellarJPYC) to "0.0084",
        Pair(stellarUSDCtest, fiatCAD) to "1.37",
        Pair(stellarUSDCprod, stellarJPYC) to "0.0084",
        Pair(stellarCircleUSDCtest, fiatUSD) to "1.05",
        Pair(stellarCircleUSDCtest, stellarJPYC) to "0.0084",
        Pair(stellarJPYC, fiatUSD) to "122",
        Pair(stellarJPYC, stellarUSDCtest) to "120",
        Pair(stellarJPYC, stellarCircleUSDCtest) to "120",
        Pair(stellarJPYC, stellarUSDCprod) to "120",
        Pair(stellarSRT, fiatUSD) to "6.9",
        Pair(stellarSRT, fiatCAD) to "2.46",
      )

    private fun getPrice(sellAsset: String, buyAsset: String): String? {
      if (sellAsset == buyAsset) {
        return "1.00"
      }
      return prices[Pair(sellAsset, buyAsset)]?.let {
        return it
      }
    }

    private fun getFee(sellAsset: String, buyAsset: String): FeeDetails {
      val feeDetails = FeeDetails("0", sellAsset)
      if (getPrice(sellAsset, buyAsset) == null) {
        return feeDetails
      }

      val sellAssetDetail = FeeDescription("Sell fee", "Fee related to selling the asset.", "1.00")
      feeDetails.addFeeDetail(sellAssetDetail)
      return feeDetails
    }
  }
}
