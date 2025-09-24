package org.stellar.anchor.platform.utils

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.AnchorException
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
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
    every { assetService.listAllAssets() } returns listOf()
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
}
