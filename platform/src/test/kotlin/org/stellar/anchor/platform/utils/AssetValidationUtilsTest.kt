package org.stellar.anchor.platform.utils

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.time.Duration
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.asset.AssetInfo
import org.stellar.anchor.api.exception.AnchorException
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
import org.stellar.anchor.api.shared.FeeDescription
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService

class AssetValidationUtilsTest {

  companion object {
    private const val fiatUSD = "iso4217:USD"
  }

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
  }

  @Test
  fun test_validateAsset_Amount_failure() {
    // fails if amount_in.amount is null
    var assetAmount = AmountAssetRequest(null, null)
    var ex =
      assertThrows<AnchorException> {
        AssetValidationUtils.validateAssetAmount("amount_in", assetAmount, assetService)
      }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("amount_in.amount cannot be empty", ex.message)

    // fails if amount_in.amount is empty
    assetAmount = AmountAssetRequest("", null)
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", assetAmount, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("amount_in.amount cannot be empty", ex.message)

    // fails if amount_in.amount is invalid
    assetAmount = AmountAssetRequest("abc", null)
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", assetAmount, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("amount_in.amount is invalid", ex.message)

    // fails if amount_in.amount is negative
    assetAmount = AmountAssetRequest("-1", null)
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", assetAmount, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("amount_in.amount should be positive", ex.message)

    // fails if amount_in.amount is zero
    assetAmount = AmountAssetRequest("0", null)
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", assetAmount, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("amount_in.amount should be positive", ex.message)

    // fails if amount_in.asset is empty
    assetAmount = AmountAssetRequest("10", "")
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", assetAmount, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("amount_in.asset cannot be empty", ex.message)

    // fails if listAllAssets is empty
    every { assetService.getAssets() } returns listOf()
    val mockAsset = AmountAssetRequest("10", fiatUSD)
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", mockAsset, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("'${fiatUSD}' is not a supported asset.", ex.message)

    // fails if listAllAssets does not contain the desired asset
    ex = assertThrows {
      AssetValidationUtils.validateAssetAmount("amount_in", mockAsset, assetService)
    }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
    Assertions.assertEquals("'${fiatUSD}' is not a supported asset.", ex.message)
  }

  @Test
  fun test_validateAssetAmount() {
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    val mockAsset = AmountAssetRequest("10", fiatUSD)
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateAssetAmount("amount_in", mockAsset, assetService)
    }
    val mockAssetWrongAmount = AmountAssetRequest("10.001", fiatUSD)

    val ex =
      assertThrows<AnchorException> {
        AssetValidationUtils.validateAssetAmount("amount_in", mockAssetWrongAmount, assetService)
      }
    Assertions.assertInstanceOf(BadRequestException::class.java, ex)
  }

  private fun feeAssetService(): AssetService {
    val asset = mockk<AssetInfo>(relaxed = true)
    every { asset.id } returns fiatUSD
    every { asset.significantDecimals } returns null
    every { assetService.assets } returns listOf(asset)
    return assetService
  }

  private fun fee(total: String, vararg amounts: String?): FeeDetails =
    FeeDetails(total, fiatUSD, amounts.map { FeeDescription("svc", it) })

  private fun validationError(fee: FeeDetails): String? =
    assertThrows<BadRequestException> {
        AssetValidationUtils.validateFeeDetails(fee, null, feeAssetService())
      }
      .message

  @ParameterizedTest
  @ValueSource(strings = ["1e20000000", "1e21", "100000000000000000000.5"])
  fun test_validateFeeDetails_detailAmountWithTooManyIntegerDigits(amount: String) {
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", amount)),
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["1e-20000000", "1e-21"])
  fun test_validateFeeDetails_detailAmountWithTooManyFractionalDigits(amount: String) {
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", amount)),
    )
  }

  @Test
  fun test_validateFeeDetails_poisonedDetailWinsOverTotalMismatch() {
    // total (1) differs from the details sum; the per-detail error must be reported, not the sum
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", "1e20000000")),
    )
  }

  @Test
  fun test_validateFeeDetails_poisonedDetailRejectedWithinOneSecond() {
    val fee = fee("1", "1e20000000")
    val service = feeAssetService()
    assertTimeoutPreemptively(Duration.ofSeconds(1)) {
      assertThrows<BadRequestException> {
        AssetValidationUtils.validateFeeDetails(fee, null, service)
      }
    }
  }

  @Test
  fun test_validateFeeDetails_messageNamesTheOffendingIndex() {
    Assertions.assertEquals(
      "fee_details.details[2].amount is invalid",
      validationError(fee("2", "1", "1", "1e20000000")),
    )
  }

  @Test
  fun test_validateFeeDetails_emptyDetailAmount() {
    val expected = "fee_details.details[0].amount cannot be empty"
    Assertions.assertEquals(expected, validationError(fee("1", null)))
    Assertions.assertEquals(expected, validationError(fee("1", "")))
  }

  @Test
  fun test_validateFeeDetails_nonNumericDetailAmount() {
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", "abc")),
    )
  }

  @Test
  fun test_validateFeeDetails_negativeDetailAmount() {
    Assertions.assertEquals(
      "fee_details.details[1].amount should be non-negative",
      validationError(fee("0", "1", "-1")),
    )
  }

  @Test
  fun test_validateFeeDetails_nullDetailElement() {
    val fee = FeeDetails("1", fiatUSD, listOf(FeeDescription("svc", "1"), null))
    Assertions.assertEquals("fee_details.details[1] cannot be null", validationError(fee))
  }

  @Test
  fun test_validateFeeDetails_validDetails() {
    val service = feeAssetService()
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("3.5", "1", "2.5"), null, service)
    }
    // a zero line is accepted
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("1", "0", "1"), null, service)
    }
    // exponent forms that strip to an in-bound value are accepted like their plain form
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("11", "100e-2", "1.0E+1"), null, service)
    }
  }

  @Test
  fun test_validateFeeDetails_detailAmountsAtTheBound() {
    val service = feeAssetService()
    val twentyIntegerDigits = "10000000000000000000"
    val twentyFractionalDigits = "0.00000000000000000001"
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(
        fee(twentyIntegerDigits, twentyIntegerDigits),
        null,
        service,
      )
    }
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(
        fee(twentyFractionalDigits, twentyFractionalDigits),
        null,
        service,
      )
    }
  }

  @Test
  fun test_validateFeeDetails_validDetailsWithMismatchedTotal() {
    Assertions.assertEquals(
      "fee_details.total is not equal to the sum of (fee_details.details.amount)",
      validationError(fee("3", "1", "1")),
    )
  }

  @Test
  fun test_validateFeeDetails_nullDetailsAreSkipped() {
    val service = feeAssetService()
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(FeeDetails("1", fiatUSD, null), null, service)
    }
  }

  @Test
  fun test_validateFeeDetails_emptyDetailsListIsComparedAsZero() {
    val service = feeAssetService()
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("0"), null, service)
    }
    Assertions.assertEquals(
      "fee_details.total is not equal to the sum of (fee_details.details.amount)",
      validationError(fee("1")),
    )
  }

  @Test
  fun test_validateFeeDetails_outOfBigIntegerRangeExponent() {
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", "1e2000000000")),
    )
  }
}
