package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.api.asset.DepositWithdrawOperation
import org.stellar.anchor.api.asset.Sep6Info
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.client.CustodialClient

class SepRequestValidatorTest {
  @MockK(relaxed = true) lateinit var assetService: AssetService
  @MockK(relaxed = true) lateinit var clientService: ClientService

  private lateinit var requestValidator: SepRequestValidator

  private val TOKEN_ACCOUNT = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  private val OTHER_ACCOUNT = "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO"

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    requestValidator = SepRequestValidator(assetService, clientService)
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

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "1.0E+500000000",
        "1.0E+20",
        "9.99E+999999999",
        "1E+21",
        "-1.0E+500000000",
        "1.0E-21",
        "0.000000000000000000001",
      ]
  )
  fun `test static validateAmount rejects extreme exponents`(amount: String) {
    assertThrows<BadRequestException> { SepRequestValidator.validateAmount("", amount, false) }
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "1.0E+5",
        "100000",
        "1.23",
        "0.01",
        "999999999999.9999",
        "1.0E+10",
        "0.0001",
        "1.23E+2",
        "9999999999.99",
        "100.00",
        "1.000000",
      ]
  )
  fun `test static validateAmount accepts reasonable amounts`(amount: String) {
    SepRequestValidator.validateAmount("", amount, false)
  }

  private fun mockToken(account: String): WebAuthJwt {
    val token = mockk<WebAuthJwt>()
    every { token.account } returns account
    return token
  }

  @Test
  fun `validateDestinationAccount passes when destination matches token subject`() {
    val token = mockToken(TOKEN_ACCOUNT)
    assertDoesNotThrow { requestValidator.validateDestinationAccount(token, TOKEN_ACCOUNT) }
  }

  @Test
  fun `validateDestinationAccount rejects when no client config and accounts differ`() {
    val token = mockToken(TOKEN_ACCOUNT)
    every { clientService.getClientConfigBySigningKey(TOKEN_ACCOUNT) } returns null
    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount(token, OTHER_ACCOUNT)
    }
  }

  @Test
  fun `validateDestinationAccount allows when allowAnyDestination is true`() {
    val token = mockToken(TOKEN_ACCOUNT)
    val clientConfig = mockk<CustodialClient>()
    every { clientService.getClientConfigBySigningKey(TOKEN_ACCOUNT) } returns clientConfig
    every { clientConfig.isAllowAnyDestination } returns true
    assertDoesNotThrow { requestValidator.validateDestinationAccount(token, OTHER_ACCOUNT) }
  }

  @Test
  fun `validateDestinationAccount allows when destination is in allowlist`() {
    val token = mockToken(TOKEN_ACCOUNT)
    val clientConfig = mockk<CustodialClient>()
    every { clientService.getClientConfigBySigningKey(TOKEN_ACCOUNT) } returns clientConfig
    every { clientConfig.isAllowAnyDestination } returns false
    every { clientConfig.destinationAccounts } returns setOf(OTHER_ACCOUNT)
    assertDoesNotThrow { requestValidator.validateDestinationAccount(token, OTHER_ACCOUNT) }
  }

  @Test
  fun `validateDestinationAccount rejects when destination is not in allowlist`() {
    val token = mockToken(TOKEN_ACCOUNT)
    val clientConfig = mockk<CustodialClient>()
    val thirdAccount = "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"
    every { clientService.getClientConfigBySigningKey(TOKEN_ACCOUNT) } returns clientConfig
    every { clientConfig.isAllowAnyDestination } returns false
    every { clientConfig.destinationAccounts } returns setOf(OTHER_ACCOUNT)
    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount(token, thirdAccount)
    }
  }

  @Test
  fun `validateDestinationAccount rejects when allowlist is empty and allowAnyDestination is false`() {
    val token = mockToken(TOKEN_ACCOUNT)
    val clientConfig = mockk<CustodialClient>()
    every { clientService.getClientConfigBySigningKey(TOKEN_ACCOUNT) } returns clientConfig
    every { clientConfig.isAllowAnyDestination } returns false
    every { clientConfig.destinationAccounts } returns emptySet()
    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount(token, OTHER_ACCOUNT)
    }
  }

  @Test
  fun `validateDestinationAccount rejects when allowlist is null and allowAnyDestination is false`() {
    val token = mockToken(TOKEN_ACCOUNT)
    val clientConfig = mockk<CustodialClient>()
    every { clientService.getClientConfigBySigningKey(TOKEN_ACCOUNT) } returns clientConfig
    every { clientConfig.isAllowAnyDestination } returns false
    every { clientConfig.destinationAccounts } returns null
    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount(token, OTHER_ACCOUNT)
    }
  }
}
