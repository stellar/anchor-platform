package org.stellar.anchor.platform.utils

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.time.Duration
import org.junit.jupiter.api.Assertions
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

  // An asset without significant decimals, so only the amounts' own validation is under test.
  private fun feeAssetService(): AssetService {
    val asset = mockk<AssetInfo>(relaxed = true)
    every { asset.id } returns fiatUSD
    every { asset.significantDecimals } returns null
    return mockk<AssetService>(relaxed = true) { every { assets } returns listOf(asset) }
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
  fun test_validateFeeDetails_poisonedDetailDoesNotHang() {
    val fee = fee("1", "1e20000000")
    val service = feeAssetService()
    // Hang backstop only: the message and ordering tests above are the deterministic oracle.
    Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10)) {
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
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("3.5", "1", "2.5"), null, feeAssetService())
    }
  }

  @Test
  fun test_validateFeeDetails_zeroDetailIsAccepted() {
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("1", "0", "1"), null, feeAssetService())
    }
  }

  @Test
  fun test_validateFeeDetails_exponentFormsAreAcceptedLikeTheirPlainForm() {
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(
        fee("11", "100e-2", "1.0E+1"),
        null,
        feeAssetService(),
      )
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["10000000000000000000", "0.00000000000000000001"])
  fun test_validateFeeDetails_detailAmountsAtTheBound(amount: String) {
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee(amount, amount), null, feeAssetService())
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
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(
        FeeDetails("1", fiatUSD, null),
        null,
        feeAssetService(),
      )
    }
  }

  @Test
  fun test_validateFeeDetails_emptyDetailsListIsComparedAsZero() {
    Assertions.assertDoesNotThrow {
      AssetValidationUtils.validateFeeDetails(fee("0"), null, feeAssetService())
    }
    Assertions.assertEquals(
      "fee_details.total is not equal to the sum of (fee_details.details.amount)",
      validationError(fee("1")),
    )
  }

  @ParameterizedTest
  // 1e2000000000 parses (below Integer.MAX_VALUE) and fails the magnitude check; the second
  // exponent overflows int, so new BigDecimal itself throws NumberFormatException.
  @ValueSource(strings = ["1e2000000000", "1e99999999999"])
  fun test_validateFeeDetails_extremeExponentIsA400(amount: String) {
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", amount)),
    )
  }

  @Test
  fun test_validateFeeDetails_totalLongerThan64CharactersIsRejected() {
    Assertions.assertEquals(
      "fee_details.amount is invalid",
      validationError(fee("1." + "0".repeat(63), "1")),
    )
  }

  @Test
  fun test_validateFeeDetails_detailLongerThan64CharactersIsRejected() {
    // Valid magnitude (strips to 1) and matches the total, so only the length cap rejects it.
    Assertions.assertEquals(
      "fee_details.details[1].amount is invalid",
      validationError(fee("2", "1", "1." + "0".repeat(63))),
    )
  }

  @Test
  fun test_validateFeeDetails_200000DigitDetailIsRejected() {
    Assertions.assertEquals(
      "fee_details.details[0].amount is invalid",
      validationError(fee("1", "1" + "0".repeat(200_000))),
    )
  }
}
