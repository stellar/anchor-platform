@file:Suppress("unused")

package org.stellar.anchor.sep24

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.net.URI
import java.nio.charset.Charset
import java.time.Instant
import org.apache.hc.core5.net.URLEncodedUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.MoreInfoUrlConstructor
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_AMOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET_ISSUER_ACCOUNT_ID
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_NAME
import org.stellar.anchor.TestConstants.Companion.TEST_HOME_DOMAIN
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.TestConstants.Companion.TEST_OFFCHAIN_ASSET
import org.stellar.anchor.TestConstants.Companion.TEST_QUOTE_ID
import org.stellar.anchor.TestConstants.Companion.TEST_TRANSACTION_ID_0
import org.stellar.anchor.TestConstants.Companion.TEST_TRANSACTION_ID_1
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.exception.*
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.sep.sep24.GetTransactionRequest
import org.stellar.anchor.api.sep.sep24.GetTransactionsRequest
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.JwtService.CLIENT_DOMAIN
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt
import org.stellar.anchor.client.*
import org.stellar.anchor.config.*
import org.stellar.anchor.event.EventService
import org.stellar.anchor.sep38.PojoSep38Quote
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.sep6.ExchangeAmountsCalculator
import org.stellar.anchor.setupMock
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.MemoHelper.makeMemo
import org.stellar.sdk.MemoHash
import org.stellar.sdk.MemoId
import org.stellar.sdk.MemoText
import org.stellar.sdk.Network.TESTNET

internal class Sep24ServiceTest {
  companion object {
    const val TEST_SEP24_INTERACTIVE_URL = "https://test-anchor.stellar.org"
    const val TEST_SEP24_MORE_INFO_URL = "https://test-anchor.stellar.org/more_info_url"
    val TEST_STARTED_AT: Instant = Instant.now()
    val TEST_COMPLETED_AT: Instant = Instant.now().plusSeconds(100)
    val DEPOSIT_QUOTE_JSON =
      """
       {
        "id": "test-deposit-quote-id",
        "expires_at": "2021-04-30T07:42:23",
        "total_price": "5.42",
        "price": "5.00",
        "sell_asset": "iso4217:USD",
        "sell_amount": "542",
        "buy_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "buy_amount": "100",
        "fee": {
          "total": "42.00",
          "asset": "iso4217:USD"
        }
      }
      """
        .trimIndent()
    val WITHDRAW_QUOTE_JSON =
      """
      {
        "id": "test-withdraw-quote-id",
        "expires_at": "2021-04-30T07:42:23",
        "total_price": "0.542",
        "price": "0.5",
        "sell_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "sell_amount": "542",
        "buy_asset": "iso4217:USD",
        "buy_amount": "1000",
        "fee": {
          "total": "42",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        }
      }
    """
        .trimIndent()
  }

  @MockK(relaxed = true) lateinit var appConfig: AppConfig

  @MockK(relaxed = true) lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) lateinit var custodySecretConfig: CustodySecretConfig

  @MockK(relaxed = true) lateinit var sep24Config: Sep24Config

  @MockK(relaxed = true) lateinit var eventService: EventService

  @MockK(relaxed = true) lateinit var clientFinder: ClientFinder

  @MockK(relaxed = true) lateinit var txnStore: Sep24TransactionStore

  @MockK(relaxed = true) lateinit var interactiveUrlConstructor: InteractiveUrlConstructor

  @MockK(relaxed = true) lateinit var moreInfoUrlConstructor: MoreInfoUrlConstructor

  @MockK(relaxed = true) lateinit var custodyConfig: CustodyConfig

  @MockK(relaxed = true) lateinit var sep38QuoteStore: Sep38QuoteStore

  @MockK(relaxed = true) lateinit var clientService: ClientService

  private val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")

  private lateinit var jwtService: JwtService
  private lateinit var sep24Service: Sep24Service
  private lateinit var testInteractiveUrlJwt: Sep24InteractiveUrlJwt
  private lateinit var depositQuote: PojoSep38Quote
  private lateinit var withdrawQuote: PojoSep38Quote
  private lateinit var calculator: ExchangeAmountsCalculator

  private val gson = GsonUtils.getInstance()

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { appConfig.stellarNetworkPassphrase } returns TESTNET.networkPassphrase
    secretConfig.setupMock()
    every { txnStore.newInstance() } returns PojoSep24Transaction()

    jwtService = spyk(JwtService(secretConfig, custodySecretConfig))
    testInteractiveUrlJwt = createTestInteractiveJwt(null)
    val strToken = jwtService.encode(testInteractiveUrlJwt)
    every { interactiveUrlConstructor.construct(any(), any(), any(), any()) } returns
      "${TEST_SEP24_INTERACTIVE_URL}?lang=en&token=$strToken"
    every { moreInfoUrlConstructor.construct(any(), any()) } returns
      "${TEST_SEP24_MORE_INFO_URL}?lang=en&token=$strToken"
    every { clientService.getClientConfigByDomain(any()) } returns
      NonCustodialClientConfig(
        "reference",
        setOf("wallet-server:8092"),
        "http://wallet-server:8092/callbacks"
      )
    every { clientService.getClientConfigBySigningKey(any()) } returns
      CustodialClientConfig(
        "referenceCustodial",
        setOf("GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"),
        null,
        false,
        null,
      )
    every { clientFinder.getClientName(any()) } returns TEST_CLIENT_NAME
    calculator = ExchangeAmountsCalculator(sep38QuoteStore)

    sep24Service =
      Sep24Service(
        appConfig,
        sep24Config,
        clientService,
        assetService,
        jwtService,
        clientFinder,
        txnStore,
        eventService,
        interactiveUrlConstructor,
        moreInfoUrlConstructor,
        custodyConfig,
        calculator
      )
    depositQuote = gson.fromJson(DEPOSIT_QUOTE_JSON, PojoSep38Quote::class.java)
    withdrawQuote = gson.fromJson(WITHDRAW_QUOTE_JSON, PojoSep38Quote::class.java)
  }

  @Test
  fun `test withdraw`() {
    val strToken = jwtService.encode(createTestInteractiveJwt(null))
    every { interactiveUrlConstructor.construct(any(), any(), any(), any()) } returns
      "${TEST_SEP24_INTERACTIVE_URL}?lang=en&token=$strToken"

    val slotTxn = slot<Sep24Transaction>()

    every { txnStore.save(capture(slotTxn)) } returns null

    val response = sep24Service.withdraw(createTestSep10JwtToken(), createTestTransactionRequest())

    verify(exactly = 1) { txnStore.save(any()) }

    assertEquals(response.type, "interactive_customer_info_needed")
    assert(response.url.startsWith(TEST_SEP24_INTERACTIVE_URL))
    assertNotNull(response.id)

    assertEquals("incomplete", slotTxn.captured.status)
    assertEquals("withdrawal", slotTxn.captured.kind)
    assertEquals("USDC", slotTxn.captured.requestAssetCode)
    assertEquals(
      slotTxn.captured.requestAssetIssuer,
      "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    )
    assertEquals(TEST_ACCOUNT, slotTxn.captured.fromAccount)
    assertEquals(TEST_CLIENT_DOMAIN, slotTxn.captured.clientDomain)
    assertEquals(TEST_CLIENT_NAME, slotTxn.captured.clientName)
    assertEquals(TEST_AMOUNT, slotTxn.captured.amountExpected)
    assertEquals(TEST_OFFCHAIN_ASSET, slotTxn.captured.amountOutAsset)
    assertNull(slotTxn.captured.amountInAsset)
    assertNull(slotTxn.captured.withdrawAnchorAccount)

    val params = URLEncodedUtils.parse(URI(response.url), Charset.forName("UTF-8"))
    val tokenStrings = params.filter { pair -> pair.name.equals("token") }
    assertEquals(1, tokenStrings.size)
    val tokenString = tokenStrings[0].value
    val decodedToken = jwtService.decode(tokenString, Sep24InteractiveUrlJwt::class.java)
    assertEquals(TEST_ACCOUNT, decodedToken.sub)
    assertEquals(TEST_CLIENT_DOMAIN, decodedToken.claims()[CLIENT_DOMAIN])
    decodedToken.claims["data"]
    assertEquals("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO", decodedToken.sub)
    assertEquals("test.client.stellar.org", decodedToken.claims["client_domain"])
    assertEquals(
      "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      decodedToken.claims["asset"]
    )
  }

  @Test
  fun `test withdraw with token memo`() {
    val strToken = jwtService.encode(createTestInteractiveJwt(TEST_MEMO))
    every { interactiveUrlConstructor.construct(any(), any(), any(), any()) } returns
      "${TEST_SEP24_INTERACTIVE_URL}?lang=en&token=$strToken"
    val slotTxn = slot<Sep24Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val response =
      sep24Service.withdraw(createTestSep10JwtWithMemo(), createTestTransactionRequest())
    val params = URLEncodedUtils.parse(URI(response.url), Charset.forName("UTF-8"))
    val tokenStrings = params.filter { pair -> pair.name.equals("token") }
    assertEquals(tokenStrings.size, 1)
    val tokenString = tokenStrings[0].value
    val decodedToken = jwtService.decode(tokenString, Sep24InteractiveUrlJwt::class.java)
    assertEquals(
      "$TEST_ACCOUNT:$TEST_MEMO",
      decodedToken.sub,
    )
    assertEquals(TEST_CLIENT_DOMAIN, decodedToken.claims()[CLIENT_DOMAIN])

    assertEquals("incomplete", slotTxn.captured.status)
    assertEquals("withdrawal", slotTxn.captured.kind)
    assertEquals("USDC", slotTxn.captured.requestAssetCode)
    assertEquals(
      slotTxn.captured.requestAssetIssuer,
      "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    )
    assertEquals(TEST_ACCOUNT, slotTxn.captured.sep10Account)
    assertEquals(TEST_MEMO, slotTxn.captured.sep10AccountMemo)
    assertEquals(TEST_ACCOUNT, slotTxn.captured.fromAccount)
    assertEquals(TEST_CLIENT_DOMAIN, slotTxn.captured.clientDomain)
    assertEquals(TEST_CLIENT_NAME, slotTxn.captured.clientName)
    assertNull(slotTxn.captured.withdrawAnchorAccount)
  }

  @Test
  fun `test withdraw with quote_id`() {
    val slotTxn = slot<Sep24Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null
    every { sep38QuoteStore.findByQuoteId(any()) } returns withdrawQuote
    sep24Service.withdraw(
      createTestSep10JwtWithMemo(),
      createTestTransactionRequest(withdrawQuote.id)
    )
    assertEquals(withdrawQuote.id, slotTxn.captured.quoteId)
    assertEquals(withdrawQuote.buyAsset, slotTxn.captured.amountOutAsset)
  }

  @Test
  fun `test withdraw with user_action_required_by`() {
    val slotTxn = slot<Sep24Transaction>()
    val deadline = 100L
    every { txnStore.save(capture(slotTxn)) } returns null
    every { sep24Config.initialUserDeadlineSeconds } returns deadline
    sep24Service.withdraw(createTestSep10JwtWithMemo(), createTestTransactionRequest())
    val dbDeadline = slotTxn.captured.userActionRequiredBy.epochSecond
    val expectedDeadline = Instant.now().plusSeconds(deadline).epochSecond
    Assertions.assertTrue(
      dbDeadline >= expectedDeadline - 2,
      "Expected $expectedDeadline got $dbDeadline}"
    )
    Assertions.assertTrue(
      dbDeadline <= expectedDeadline,
      "Expected $expectedDeadline got $dbDeadline}"
    )
  }

  @Test
  fun `test withdrawal with no token and no request failure`() {
    assertThrows<SepValidationException> {
      sep24Service.withdraw(null, createTestTransactionRequest())
    }

    assertThrows<SepValidationException> { sep24Service.withdraw(createTestSep10JwtToken(), null) }
  }

  @Test
  fun `test withdraw with bad requests`() {
    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request.remove("asset_code")
      sep24Service.withdraw(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["account"] = "G1234"
      sep24Service.withdraw(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["asset_code"] = "USDC_NA"
      sep24Service.withdraw(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["amount"] = "0"
      sep24Service.withdraw(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["amount"] = "10001"
      sep24Service.withdraw(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["account"] = "G1234"
      val token = createTestSep10JwtToken()
      token.sub = "G1234"
      sep24Service.withdraw(token, request)
    }

    assertThrows<BadRequestException> {
      val request = createTestTransactionRequest("bad-quote-id")
      val token = createTestSep10JwtToken()
      every { sep38QuoteStore.findByQuoteId(any()) } returns null
      sep24Service.withdraw(token, request)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["true", "false"])
  fun `test deposit`(claimableBalanceSupported: String) {
    val strToken = jwtService.encode(createTestInteractiveJwt(null))
    every { interactiveUrlConstructor.construct(any(), any(), any(), any()) } returns
      "${TEST_SEP24_INTERACTIVE_URL}?lang=en&token=$strToken"

    val slotTxn = slot<Sep24Transaction>()

    every { txnStore.save(capture(slotTxn)) } returns null

    val request = createTestTransactionRequest()
    request["claimable_balance_supported"] = claimableBalanceSupported
    val response = sep24Service.deposit(createTestSep10JwtToken(), request)

    verify(exactly = 1) { txnStore.save(any()) }

    assertEquals(response.type, "interactive_customer_info_needed")
    assertTrue(response.url.startsWith(TEST_SEP24_INTERACTIVE_URL))
    assertEquals(response.id, slotTxn.captured.transactionId)

    assertEquals("incomplete", slotTxn.captured.status)
    assertEquals("deposit", slotTxn.captured.kind)
    assertEquals(TEST_ASSET, slotTxn.captured.requestAssetCode)
    assertEquals(TEST_ASSET_ISSUER_ACCOUNT_ID, slotTxn.captured.requestAssetIssuer)
    assertEquals(TEST_ACCOUNT, slotTxn.captured.toAccount)
    assertEquals(TEST_CLIENT_DOMAIN, slotTxn.captured.clientDomain)
    assertEquals(TEST_CLIENT_NAME, slotTxn.captured.clientName)
    assertEquals(TEST_AMOUNT, slotTxn.captured.amountExpected)
    assertEquals(TEST_OFFCHAIN_ASSET, slotTxn.captured.amountInAsset)
    assertNull(slotTxn.captured.amountOutAsset)
  }

  @Test
  fun `test deposit with token memo`() {
    val strToken = jwtService.encode(createTestInteractiveJwt(TEST_MEMO))
    every { interactiveUrlConstructor.construct(any(), any(), any(), any()) } returns
      "${TEST_SEP24_INTERACTIVE_URL}?lang=en&token=$strToken"
    val slotTxn = slot<Sep24Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val response =
      sep24Service.deposit(createTestSep10JwtWithMemo(), createTestTransactionRequest())
    val params = URLEncodedUtils.parse(URI(response.url), Charset.forName("UTF-8"))
    val tokenStrings = params.filter { pair -> pair.name.equals("token") }
    assertEquals(tokenStrings.size, 1)
    val tokenString = tokenStrings[0].value
    val decodedToken = jwtService.decode(tokenString, Sep24InteractiveUrlJwt::class.java)
    assertEquals(
      "$TEST_ACCOUNT:$TEST_MEMO",
      decodedToken.sub,
    )
    assertEquals(TEST_CLIENT_DOMAIN, decodedToken.claims[CLIENT_DOMAIN])

    assertEquals("incomplete", slotTxn.captured.status)
    assertEquals("deposit", slotTxn.captured.kind)
    assertEquals("USDC", slotTxn.captured.requestAssetCode)
    assertEquals(
      slotTxn.captured.requestAssetIssuer,
      "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    )
    assertEquals(TEST_ACCOUNT, slotTxn.captured.sep10Account)
    assertEquals(TEST_MEMO, slotTxn.captured.sep10AccountMemo)
    assertEquals(TEST_ACCOUNT, slotTxn.captured.toAccount)
    assertEquals(TEST_CLIENT_DOMAIN, slotTxn.captured.clientDomain)
    assertEquals(TEST_CLIENT_NAME, slotTxn.captured.clientName)
  }

  @Test
  fun `test deposit with quote_id`() {
    val slotTxn = slot<Sep24Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null
    every { sep38QuoteStore.findByQuoteId(any()) } returns depositQuote
    sep24Service.deposit(
      createTestSep10JwtWithMemo(),
      createTestTransactionRequest(depositQuote.id)
    )
    assertEquals(depositQuote.id, slotTxn.captured.quoteId)
    assertEquals(depositQuote.sellAsset, slotTxn.captured.amountInAsset)
  }

  @Test
  fun `test deposit with user_action_required_by`() {
    val slotTxn = slot<Sep24Transaction>()
    val deadline = 100L
    every { txnStore.save(capture(slotTxn)) } returns null
    every { sep24Config.initialUserDeadlineSeconds } returns deadline
    sep24Service.deposit(createTestSep10JwtWithMemo(), createTestTransactionRequest())
    val dbDeadline = slotTxn.captured.userActionRequiredBy.epochSecond
    val expectedDeadline = Instant.now().plusSeconds(deadline).epochSecond
    Assertions.assertTrue(
      dbDeadline >= expectedDeadline - 2,
      "Expected $expectedDeadline got $dbDeadline}"
    )
    Assertions.assertTrue(
      dbDeadline <= expectedDeadline,
      "Expected $expectedDeadline got $dbDeadline}"
    )
  }

  @Test
  fun `test deposit with no token and no request`() {
    assertThrows<SepValidationException> {
      sep24Service.deposit(null, createTestTransactionRequest())
    }

    assertThrows<SepValidationException> { sep24Service.deposit(createTestSep10JwtToken(), null) }
  }

  @Test
  fun `test deposit to unknown account`() {
    val request = createTestTransactionRequest()
    val unknownAccount = "GC6TP2RCW665CBOTMR5Q2JXNRK77FWV2FCTHNQXS3FNDMWZCGJBJ4QCY"
    request["account"] = unknownAccount

    val ex =
      assertThrows<SepValidationException> {
        sep24Service.deposit(createTestSep10JwtToken(), request)
      }
    assertEquals(Sep24Service.ERR_TOKEN_ACCOUNT_MISMATCH, ex.message)
  }

  @Test
  fun `test deposit to whitelisted account`() {
    val strToken = jwtService.encode(createTestInteractiveJwt(null))
    every { interactiveUrlConstructor.construct(any(), any(), any(), any()) } returns
      "${TEST_SEP24_INTERACTIVE_URL}?lang=en&token=$strToken"
    val slotTxn = slot<Sep24Transaction>()
    every { txnStore.save(capture(slotTxn)) } returns null

    val whitelistedAccount = "GC6TP2RCW665CBOTMR5Q2JXNRK77FWV2FCTHNQXS3FNDMWZCGJBJ4QCY"
    every { clientService.getClientConfigBySigningKey(any()) } returns
      CustodialClientConfig(
        "referenceCustodial",
        setOf("GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"),
        null,
        false,
        setOf(whitelistedAccount),
      )
    val request = createTestTransactionRequest()
    request["account"] = whitelistedAccount

    val response = sep24Service.deposit(createTestSep10JwtToken(), request)

    verify(exactly = 1) { txnStore.save(any()) }

    assertEquals(response.type, "interactive_customer_info_needed")
    assertTrue(response.url.startsWith(TEST_SEP24_INTERACTIVE_URL))
    assertEquals(response.id, slotTxn.captured.transactionId)

    assertEquals("incomplete", slotTxn.captured.status)
    assertEquals("deposit", slotTxn.captured.kind)
    assertEquals(TEST_ASSET, slotTxn.captured.requestAssetCode)
    assertEquals(TEST_ASSET_ISSUER_ACCOUNT_ID, slotTxn.captured.requestAssetIssuer)
    assertEquals(whitelistedAccount, slotTxn.captured.toAccount)
    assertEquals(TEST_CLIENT_DOMAIN, slotTxn.captured.clientDomain)
    assertEquals(TEST_CLIENT_NAME, slotTxn.captured.clientName)
  }

  @Test
  fun `test deposit with bad requests`() {
    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request.remove("asset_code")
      sep24Service.deposit(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["account"] = "G1234"
      sep24Service.deposit(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["asset_code"] = "USDC_NA"
      sep24Service.deposit(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["amount"] = "0"
      sep24Service.deposit(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["amount"] = "10001"
      sep24Service.deposit(createTestSep10JwtToken(), request)
    }

    assertThrows<SepValidationException> {
      val request = createTestTransactionRequest()
      request["account"] = "G1234"
      val token = createTestSep10JwtToken()
      token.sub = "G1234"
      sep24Service.deposit(token, request)
    }

    assertThrows<BadRequestException> {
      val request = createTestTransactionRequest("bad-quote-id")
      val token = createTestSep10JwtToken()
      every { sep38QuoteStore.findByQuoteId(any()) } returns null
      sep24Service.deposit(token, request)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun `test find transactions`(kind: String) {
    every { txnStore.findTransactions(TEST_ACCOUNT, any(), any()) } returns
      createTestTransactions(kind)
    val gtr =
      GetTransactionsRequest.of(TEST_ASSET, kind, 10, "2021-12-20T19:30:58+00:00", "1", "en-US")
    val response = sep24Service.findTransactions(createTestSep10JwtToken(), gtr)

    assertEquals(response.transactions.size, 2)
    assertEquals(response.transactions[0].id, TEST_TRANSACTION_ID_0)
    assertEquals(response.transactions[0].status, "incomplete")
    assertEquals(response.transactions[0].kind, kind)
    assertEquals(response.transactions[0].startedAt, TEST_STARTED_AT)
    assertEquals(response.transactions[0].completedAt, TEST_COMPLETED_AT)
    assertEquals(response.transactions[0].quoteId, TEST_QUOTE_ID)

    assertEquals(response.transactions[1].id, TEST_TRANSACTION_ID_1)
    assertEquals(response.transactions[1].status, "completed")
    assertEquals(response.transactions[1].kind, kind)
    assertEquals(response.transactions[1].startedAt, TEST_STARTED_AT)
    assertEquals(response.transactions[1].completedAt, TEST_COMPLETED_AT)
    assertEquals(response.transactions[1].quoteId, TEST_QUOTE_ID)
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      ["stellar:native", "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"]
  )
  fun `test find transactions with different asset code types`(assetCode: String) {
    every { txnStore.findTransactions(TEST_ACCOUNT, any(), any()) } returns
      createTestTransactions("deposit")
    val gtr =
      GetTransactionsRequest.of(assetCode, "deposit", 10, "2021-12-20T19:30:58+00:00", "1", "en-US")
    val response = sep24Service.findTransactions(createTestSep10JwtToken(), gtr)

    assertEquals(response.transactions.size, 2)
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun `test find transactions with validation error`(kind: String) {
    assertThrows<SepNotAuthorizedException> {
      val gtr =
        GetTransactionsRequest.of(TEST_ASSET, kind, 10, "2021-12-20T19:30:58+00:00", "1", "en-US")
      sep24Service.findTransactions(null, gtr)
    }

    assertThrows<SepValidationException> {
      val gtr =
        GetTransactionsRequest.of(
          "BAD_ASSET_CODE",
          kind,
          10,
          "2021-12-20T19:30:58+00:00",
          "1",
          "en-US"
        )
      sep24Service.findTransactions(createTestSep10JwtToken(), gtr)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun `test find one transaction`(kind: String) {
    every { txnStore.findByTransactionId(any()) } returns createTestTransaction(kind)

    var gtr = GetTransactionRequest(TEST_TRANSACTION_ID_0, null, null, "en-US")
    val response = sep24Service.findTransaction(createTestSep10JwtToken(), gtr)

    assertEquals(TEST_TRANSACTION_ID_0, response.transaction.id)
    assertEquals("incomplete", response.transaction.status)
    assertEquals(kind, response.transaction.kind)
    assertEquals(TEST_STARTED_AT, response.transaction.startedAt)
    assertEquals(TEST_COMPLETED_AT, response.transaction.completedAt)
    verify(exactly = 1) { txnStore.findByTransactionId(TEST_TRANSACTION_ID_0) }

    // test with stellar transaction_id
    every { txnStore.findByStellarTransactionId(any()) } returns createTestTransaction("deposit")
    gtr = GetTransactionRequest(null, TEST_TRANSACTION_ID_0, null, "en-US")
    sep24Service.findTransaction(createTestSep10JwtToken(), gtr)

    verify(exactly = 1) { txnStore.findByStellarTransactionId(TEST_TRANSACTION_ID_0) }

    // test with external transaction_id
    every { txnStore.findByExternalTransactionId(any()) } returns createTestTransaction("deposit")
    gtr = GetTransactionRequest(null, null, TEST_TRANSACTION_ID_0, "en-US")
    sep24Service.findTransaction(createTestSep10JwtToken(), gtr)

    verify(exactly = 1) { txnStore.findByExternalTransactionId(TEST_TRANSACTION_ID_0) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun `test find transaction validation error`(kind: String) {
    assertThrows<SepNotAuthorizedException> {
      val gtr = GetTransactionRequest(TEST_TRANSACTION_ID_0, null, null, "en-US")
      sep24Service.findTransaction(null, gtr)
    }

    assertThrows<SepValidationException> {
      sep24Service.findTransaction(createTestSep10JwtToken(), null)
    }

    assertThrows<SepValidationException> {
      val gtr = GetTransactionRequest(null, null, null, "en-US")
      sep24Service.findTransaction(createTestSep10JwtToken(), gtr)
    }

    every { txnStore.findByTransactionId(any()) } returns null
    assertThrows<SepNotFoundException> {
      val gtr = GetTransactionRequest(TEST_TRANSACTION_ID_0, null, null, "en-US")
      sep24Service.findTransaction(createTestSep10JwtToken(), gtr)
    }

    val badTxn = createTestTransaction(kind)
    badTxn.kind = "na"
    every { txnStore.findByTransactionId(any()) } returns badTxn

    assertThrows<SepException> {
      val gtr = GetTransactionRequest(TEST_TRANSACTION_ID_0, null, null, "en-US")
      sep24Service.findTransaction(createTestSep10JwtToken(), gtr)
    }
  }

  @Test
  fun `test GET info`() {
    val response = sep24Service.info

    assertEquals(3, response.deposit.size)
    assertEquals(2, response.withdraw.size)
    assertNotNull(response.deposit["USDC"])
    assertNotNull(response.withdraw["USDC"])
    assertFalse(response.fee.enabled)
    assertFalse(response.features.accountCreation)
    assertFalse(response.features.claimableBalances)
  }

  @Test
  fun `test make memo`() {
    var memo = makeMemo("this_is_a_test_memo", "text")
    assertTrue(memo is MemoText)
    memo = makeMemo("1234", "id")
    assertTrue(memo is MemoId)
    memo = makeMemo("YzVlMzg5ZDMtZGQ4My00NDlmLThhMDctYTUwM2MwM2U=", "hash")
    assertTrue(memo is MemoHash)
  }

  @Test
  fun `test lang`() {
    val slotTxn = slot<Sep24Transaction>()

    every { txnStore.save(capture(slotTxn)) } returns null

    val request = createTestTransactionRequest()
    request["lang"] = "en"
    val response = sep24Service.withdraw(createTestSep10JwtToken(), request)
    assertTrue(response.url.indexOf("lang=en") != -1)
  }

  private fun createTestSep10JwtToken(): Sep10Jwt {
    return TestHelper.createSep10Jwt(TEST_ACCOUNT, null, TEST_HOME_DOMAIN, TEST_CLIENT_DOMAIN)
  }

  private fun createTestSep10JwtWithMemo(): Sep10Jwt {
    return TestHelper.createSep10Jwt(TEST_ACCOUNT, TEST_MEMO, TEST_HOME_DOMAIN, TEST_CLIENT_DOMAIN)
  }

  private fun createTestInteractiveJwt(accountMemo: String?): Sep24InteractiveUrlJwt {
    val jwt =
      Sep24InteractiveUrlJwt(
        if (accountMemo == null) TEST_ACCOUNT else "$TEST_ACCOUNT:$accountMemo",
        TEST_TRANSACTION_ID_0,
        Instant.now().epochSecond + 1000,
        TEST_CLIENT_DOMAIN,
        TEST_CLIENT_NAME,
        TEST_HOME_DOMAIN
      )
    jwt.claim("asset", "stellar:${TEST_ASSET}:${TEST_ASSET_ISSUER_ACCOUNT_ID}")
    return jwt
  }
}
