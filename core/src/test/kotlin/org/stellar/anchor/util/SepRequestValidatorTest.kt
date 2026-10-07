package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.asset.DepositWithdrawOperation
import org.stellar.anchor.api.asset.Sep6Info
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.client.CustodialClient

class SepRequestValidatorTest {
  @MockK(relaxed = true) lateinit var assetService: AssetService
  @MockK(relaxed = true) lateinit var clientService: ClientService

  private lateinit var requestValidator: SepRequestValidator

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

  private fun amountError(amount: String?, min: Long? = null, max: Long? = null): String? =
    assertThrows<SepValidationException> {
        requestValidator.validateAmount(amount!!, TEST_ASSET, 2, min, max)
      }
      .message

  @Test
  fun `test validateAmount rejects an empty amount without echoing it`() {
    assertEquals("invalid amount for asset USDC", amountError(""))
  }

  @Test
  fun `test validateAmount rejects an amount longer than 64 characters without echoing it`() {
    // 65 characters that parse to 1, so only the length can reject it
    val padded = "0".repeat(64) + "1"
    assertEquals(65, padded.length)
    assertEquals("invalid amount for asset USDC", amountError(padded))
  }

  @Test
  fun `test validateAmount accepts an amount of exactly 64 characters`() {
    val padded = "0".repeat(63) + "1"
    assertEquals(64, padded.length)
    requestValidator.validateAmount(padded, TEST_ASSET, 2, null, null)
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      ["abc", " ", "1,5", "1e20000000", "1E+21", "100000000000000000000", "1e-21", "1e999999999999"]
  )
  fun `test validateAmount rejects a non numeric or out of magnitude amount`(amount: String) {
    // "100000000000000000000" has 21 integer digits; "1e999999999999" overflows the exponent
    assertEquals("invalid amount $amount for asset USDC", amountError(amount))
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "10", "99999999999999999999", "1.5", "1e2"])
  fun `test validateAmount accepts a valid amount when there is no min or max`(amount: String) {
    requestValidator.validateAmount(amount, TEST_ASSET, 2, null, null)
  }

  @Test
  fun `test validateAmount still applies the scale sign and bounds checks to a bounded amount`() {
    assertEquals(
      "invalid amount 1.001 for asset USDC, significant decimals is 2",
      amountError("1.001"),
    )
    assertEquals("invalid amount 0 for asset USDC", amountError("0"))
    assertEquals("invalid amount -1 for asset USDC", amountError("-1"))
    assertEquals("invalid amount 5 for asset USDC", amountError("5", min = 10))
    assertEquals("invalid amount 500 for asset USDC", amountError("500", max = 100))
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

  @ParameterizedTest
  @ValueSource(strings = ["0e-50000000", "0e+50000000", "0.000000000000000000000", "-0e-50000000"])
  fun `test static validateAmount rejects zero with an extreme scale`(amount: String) {
    val ex =
      assertThrows<BadRequestException> { SepRequestValidator.validateAmount("", amount, true) }
    assertEquals("amount is invalid", ex.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["0", "0.00", "0.00000000000000000000", "0e+5", "0e-20"])
  fun `test static validateAmount accepts zero with a bounded scale`(amount: String) {
    SepRequestValidator.validateAmount("", amount, true)
  }

  @Test
  fun `test static validateAmount rejects amount strings longer than 64 characters`() {
    // Otherwise valid (strips to 1), so only the length cap can reject it.
    val padded = "1." + "0".repeat(63)
    assertEquals(65, padded.length)
    val ex =
      assertThrows<BadRequestException> {
        SepRequestValidator.validateAmount("fee_details.", padded, false)
      }
    assertEquals("fee_details.amount is invalid", ex.message)
  }

  @Test
  fun `test static validateAmount accepts an amount string of exactly 64 characters`() {
    val padded = "1." + "0".repeat(62)
    assertEquals(64, padded.length)
    val amount = SepRequestValidator.validateAmount("", padded, false)
    assertEquals(0, amount.compareTo(BigDecimal.ONE))
  }

  @Test
  fun `test static validateAmount applies the length cap before the sign check`() {
    // 65 characters each: the cap reports 'amount is invalid', not the sign message. The zero is
    // 65 plain digits (scale 0) so only the cap can reject it: it would otherwise be a valid zero.
    val zero = "0".repeat(65)
    val negative = "-1." + "0".repeat(62)
    assertEquals(65, zero.length)
    assertEquals(65, negative.length)
    assertEquals(
      "amount is invalid",
      assertThrows<BadRequestException> { SepRequestValidator.validateAmount("", zero, true) }
        .message,
    )
    assertEquals(
      "amount is invalid",
      assertThrows<BadRequestException> { SepRequestValidator.validateAmount("", negative, true) }
        .message,
    )
  }

  @Test
  fun `test validateDestinationAccount allows destination matching token account`() {
    val token = TestHelper.createWebAuthJwt()
    requestValidator.validateDestinationAccount(token, token.account)
  }

  @Test
  fun `test validateDestinationAccount allows muxed destination whose base account matches token`() {
    val token = TestHelper.createWebAuthJwt()
    val muxedDestination =
      org.stellar.sdk.MuxedAccount(token.account, java.math.BigInteger.valueOf(99L)).address
    requestValidator.validateDestinationAccount(token, muxedDestination)
  }

  @Test
  fun `test validateDestinationAccount allows destination on client allowlist`() {
    val token = TestHelper.createWebAuthJwt()
    val allowedAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client =
      CustodialClient.builder()
        .name("test-client")
        .destinationAccounts(setOf(allowedAccount))
        .build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    requestValidator.validateDestinationAccount(token, allowedAccount)
  }

  @Test
  fun `test validateDestinationAccount allows destination when allowAnyDestination is true`() {
    val token = TestHelper.createWebAuthJwt()
    val anyAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client = CustodialClient.builder().name("test-client").allowAnyDestination(true).build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    requestValidator.validateDestinationAccount(token, anyAccount)
  }

  @Test
  fun `test validateDestinationAccount allows destination when allowAnyDestination is true even if destinationAccounts would not contain it`() {
    val token = TestHelper.createWebAuthJwt()
    val destination = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client =
      CustodialClient.builder()
        .name("test-client")
        .allowAnyDestination(true)
        .destinationAccounts(setOf("GCEZWKCA5VLDNRLN3RPRJMRZOX3Z6G5CHCGBXT0E4EPJOS2W2YNHH6K"))
        .build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    requestValidator.validateDestinationAccount(token, destination)
  }

  @Test
  fun `test validateDestinationAccount rejects destination not on allowlist with account-not-allowed message`() {
    val token = TestHelper.createWebAuthJwt()
    val attackerAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client =
      CustodialClient.builder()
        .name("test-client")
        .destinationAccounts(setOf("GCEZWKCA5VLDNRLN3RPRJMRZOX3Z6G5CHCGBXT0E4EPJOS2W2YNHH6K"))
        .build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    val ex =
      assertThrows<SepValidationException> {
        requestValidator.validateDestinationAccount(token, attackerAccount)
      }
    assertEquals("Provided 'account' is not allowed", ex.message)
  }

  @Test
  fun `test validateDestinationAccount rejects empty destinationAccounts with token-mismatch message`() {
    val token = TestHelper.createWebAuthJwt()
    val differentAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client =
      CustodialClient.builder()
        .name("test-client")
        .allowAnyDestination(false)
        .destinationAccounts(emptySet())
        .build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    val ex =
      assertThrows<SepValidationException> {
        requestValidator.validateDestinationAccount(token, differentAccount)
      }
    assertEquals(SepRequestValidator.ERR_TOKEN_ACCOUNT_MISMATCH, ex.message)
  }

  @Test
  fun `test validateDestinationAccount rejects when client config is null and account differs`() {
    val token = TestHelper.createWebAuthJwt()
    val differentAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    every { clientService.getClientConfigBySigningKey(token.account) } returns null

    val ex =
      assertThrows<SepValidationException> {
        requestValidator.validateDestinationAccount(token, differentAccount)
      }
    assertEquals(SepRequestValidator.ERR_TOKEN_ACCOUNT_MISMATCH, ex.message)
  }

  @Test
  fun `test validateDestinationAccount rejects when client has no allowlist and allowAnyDestination is false`() {
    val token = TestHelper.createWebAuthJwt()
    val differentAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client = CustodialClient.builder().name("test-client").allowAnyDestination(false).build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    val ex =
      assertThrows<SepValidationException> {
        requestValidator.validateDestinationAccount(token, differentAccount)
      }
    assertEquals(SepRequestValidator.ERR_TOKEN_ACCOUNT_MISMATCH, ex.message)
  }

  @Test
  fun `test validateDestinationAccount rejects malformed muxed webAuthAccount`() {
    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount("MINVALIDMUXEDADDRESS", TestHelper.TEST_ACCOUNT)
    }
  }

  @Test
  fun `test validateDestinationAccount accepts muxed destination when allowlist holds the base address`() {
    val token = TestHelper.createWebAuthJwt()
    val allowedBase = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val muxedDestination =
      org.stellar.sdk.MuxedAccount(allowedBase, java.math.BigInteger.valueOf(42L)).address
    val client =
      CustodialClient.builder().name("test-client").destinationAccounts(setOf(allowedBase)).build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    requestValidator.validateDestinationAccount(token, muxedDestination)
  }

  @Test
  fun `test validateDestinationAccount rejects muxed destination not on allowlist`() {
    val token = TestHelper.createWebAuthJwt()
    val allowedBase = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val muxedDestination =
      org.stellar.sdk.MuxedAccount(allowedBase, java.math.BigInteger.valueOf(42L)).address
    val client =
      CustodialClient.builder()
        .name("test-client")
        .destinationAccounts(setOf("GCEZWKCA5VLDNRLN3RPRJMRZOX3Z6G5CHCGBXT0E4EPJOS2W2YNHH6K"))
        .build()
    every { clientService.getClientConfigBySigningKey(token.account) } returns client

    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount(token, muxedDestination)
    }
  }

  @Test
  fun `test validateDestinationAccount allows classic destination when webAuthAccount is muxed`() {
    val muxedWebAuth =
      org.stellar.sdk
        .MuxedAccount(TestHelper.TEST_ACCOUNT, java.math.BigInteger.valueOf(7L))
        .address
    requestValidator.validateDestinationAccount(muxedWebAuth, TestHelper.TEST_ACCOUNT)
  }

  @Test
  fun `test validateDestinationAccount allows muxed destination when webAuthAccount is muxed with same base`() {
    val muxedWebAuth =
      org.stellar.sdk
        .MuxedAccount(TestHelper.TEST_ACCOUNT, java.math.BigInteger.valueOf(1L))
        .address
    val muxedDestination =
      org.stellar.sdk
        .MuxedAccount(TestHelper.TEST_ACCOUNT, java.math.BigInteger.valueOf(2L))
        .address
    requestValidator.validateDestinationAccount(muxedWebAuth, muxedDestination)
  }

  @Test
  fun `test validateDestinationAccount looks up client by base address when webAuthAccount is muxed`() {
    val muxedWebAuth =
      org.stellar.sdk
        .MuxedAccount(TestHelper.TEST_ACCOUNT, java.math.BigInteger.valueOf(5L))
        .address
    val allowedAccount = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val client =
      CustodialClient.builder()
        .name("test-client")
        .destinationAccounts(setOf(allowedAccount))
        .build()
    every { clientService.getClientConfigBySigningKey(TestHelper.TEST_ACCOUNT) } returns client

    requestValidator.validateDestinationAccount(muxedWebAuth, allowedAccount)
  }

  @Test
  fun `test validateDestinationAccount accepts muxed destination whose base is on the allowlist`() {
    val muxedWebAuth =
      org.stellar.sdk
        .MuxedAccount(TestHelper.TEST_ACCOUNT, java.math.BigInteger.valueOf(3L))
        .address
    val allowedBase = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val muxedDestination =
      org.stellar.sdk.MuxedAccount(allowedBase, java.math.BigInteger.valueOf(99L)).address
    val client =
      CustodialClient.builder().name("test-client").destinationAccounts(setOf(allowedBase)).build()
    every { clientService.getClientConfigBySigningKey(TestHelper.TEST_ACCOUNT) } returns client

    requestValidator.validateDestinationAccount(muxedWebAuth, muxedDestination)
  }

  @Test
  fun `test validateDestinationAccount rejects muxed destination whose base is not on allowlist`() {
    val muxedWebAuth =
      org.stellar.sdk
        .MuxedAccount(TestHelper.TEST_ACCOUNT, java.math.BigInteger.valueOf(4L))
        .address
    val attackerBase = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    val muxedAttacker =
      org.stellar.sdk.MuxedAccount(attackerBase, java.math.BigInteger.valueOf(1L)).address
    val client =
      CustodialClient.builder()
        .name("test-client")
        .destinationAccounts(setOf("GCEZWKCA5VLDNRLN3RPRJMRZOX3Z6G5CHCGBXT0E4EPJOS2W2YNHH6K"))
        .build()
    every { clientService.getClientConfigBySigningKey(TestHelper.TEST_ACCOUNT) } returns client

    assertThrows<SepValidationException> {
      requestValidator.validateDestinationAccount(muxedWebAuth, muxedAttacker)
    }
  }
}
