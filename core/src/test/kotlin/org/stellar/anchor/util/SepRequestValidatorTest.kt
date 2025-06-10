package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.api.asset.DepositWithdrawOperation
import org.stellar.anchor.api.asset.Sep6Info
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.asset.AssetService

class SepRequestValidatorTest {
  @MockK(relaxed = true) lateinit var assetService: AssetService

  private lateinit var requestValidator: SepRequestValidator

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    requestValidator = SepRequestValidator(assetService)
  }

  @Test
  fun `test getDepositAsset`() {
    val asset = StellarAssetInfo()
    val sep6Info = mockk<Sep6Info>()
    val deposit = mockk<DepositWithdrawOperation>()
    asset.sep6 = sep6Info
    every { sep6Info.enabled } returns true
    every { sep6Info.deposit } returns deposit
    every { deposit.enabled } returns true
    every { assetService.getAsset(TEST_ASSET) } returns asset
    requestValidator.getDepositAsset(TEST_ASSET)
  }

  @Test
  fun `test getDepositAsset with invalid asset code`() {
    every { assetService.getAsset(TEST_ASSET) } returns null
    assertThrows<SepValidationException> { requestValidator.getDepositAsset(TEST_ASSET) }
  }

  @Test
  fun `test getDepositAsset with deposit disabled asset`() {
    val asset = StellarAssetInfo()
    val sep6Info = mockk<Sep6Info>()
    val deposit = mockk<DepositWithdrawOperation>()
    asset.sep6 = sep6Info
    every { sep6Info.enabled } returns true
    every { sep6Info.deposit } returns deposit
    every { deposit.enabled } returns false
    every { assetService.getAsset(TEST_ASSET) } returns asset
    assertThrows<SepValidationException> { requestValidator.getDepositAsset(TEST_ASSET) }
  }

  @Test
  fun `test getDepositAsset with sep6 disabled asset`() {
    val asset = StellarAssetInfo()
    val sep6Info = mockk<Sep6Info>()
    asset.sep6 = sep6Info
    every { sep6Info.enabled } returns false
    every { assetService.getAsset(TEST_ASSET) } returns asset
    assertThrows<SepValidationException> { requestValidator.getDepositAsset(TEST_ASSET) }
  }

  @Test
  fun `test getWithdrawAsset`() {
    val asset = StellarAssetInfo()
    val sep6Info = mockk<Sep6Info>()
    val withdraw = mockk<DepositWithdrawOperation>()
    asset.sep6 = sep6Info
    every { sep6Info.enabled } returns true
    every { sep6Info.withdraw } returns withdraw
    every { withdraw.enabled } returns true
    every { assetService.getAsset(TEST_ASSET) } returns asset
    requestValidator.getWithdrawAsset(TEST_ASSET)
  }

  @Test
  fun `test getWithdrawAsset with invalid asset code`() {
    every { assetService.getAsset(TEST_ASSET) } returns null
    assertThrows<SepValidationException> { requestValidator.getWithdrawAsset(TEST_ASSET) }
  }

  @Test
  fun `test getWithdrawAsset with withdraw disabled asset`() {
    val asset = StellarAssetInfo()
    val sep6Info = mockk<Sep6Info>()
    val withdraw = mockk<DepositWithdrawOperation>()
    asset.sep6 = sep6Info
    every { sep6Info.enabled } returns true
    every { sep6Info.withdraw } returns withdraw
    every { withdraw.enabled } returns false
    every { assetService.getAsset(TEST_ASSET) } returns asset
    assertThrows<SepValidationException> { requestValidator.getWithdrawAsset(TEST_ASSET) }
  }

  @Test
  fun `test getWithdrawAsset with sep6 disabled asset`() {
    val asset = StellarAssetInfo()
    val sep6Info = mockk<Sep6Info>()
    asset.sep6 = sep6Info
    every { sep6Info.enabled } returns false
    every { assetService.getAsset(TEST_ASSET) } returns asset
    assertThrows<SepValidationException> { requestValidator.getWithdrawAsset(TEST_ASSET) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "100", "1.00", "100.00", "50"])
  fun `test validateAmount`(amount: String) {
    requestValidator.validateAmount(amount, TEST_ASSET, 2, 1L, 100L)
  }

  @Test
  fun `test validateAmount with too high precision`() {
    assertThrows<SepValidationException> {
      requestValidator.validateAmount("1.000001", TEST_ASSET, 2, 1L, 100L)
    }
  }

  @Test
  fun `test validateAmount with too high value`() {
    assertThrows<SepValidationException> {
      requestValidator.validateAmount("101", TEST_ASSET, 2, 1L, 100L)
    }
  }

  @Test
  fun `test validateAmount with too low value`() {
    assertThrows<SepValidationException> {
      requestValidator.validateAmount("0", TEST_ASSET, 2, 1L, 100L)
    }
  }

  @ValueSource(strings = ["bank_account", "cash"])
  @ParameterizedTest
  fun `test validateTypes`(type: String) {
    requestValidator.validateTypes(type, TEST_ASSET, listOf("bank_account", "cash"))
  }

  @Test
  fun `test validateTypes with invalid type`() {
    assertThrows<SepValidationException> {
      requestValidator.validateTypes("??", TEST_ASSET, listOf("bank_account", "cash"))
    }
  }

  @ValueSource(
    strings =
      [
        "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "MBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUAAAAAAAAAPCIA2IM",
        "CAASCQKVVBSLREPEUGPOTQZ4BC2NDBY2MW7B2LGIGFUPIY4Z3XUZRVTX",
      ]
  )
  @ParameterizedTest
  fun `test validateAccount`(account: String) {
    requestValidator.validateAccount(account)
  }

  @Test
  fun `test validateAccount with invalid account`() {
    assertThrows<SepValidationException> { requestValidator.validateAccount("??") }
  }
}
