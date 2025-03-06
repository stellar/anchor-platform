@file:Suppress("unused")

package org.stellar.anchor.sep31

import com.google.gson.Gson
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.stream.Stream
import org.apache.commons.lang3.StringUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.skyscreamer.jsonassert.JSONAssert
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.asset.AssetInfo
import org.stellar.anchor.api.asset.AssetInfo.Field
import org.stellar.anchor.api.asset.Sep31Info
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.callback.*
import org.stellar.anchor.api.exception.*
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.sep.sep31.*
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest.Sep31TxnFields
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.anchor.api.shared.SepDepositInfo
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.client.ClientConfig.CallbackUrls
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.config.*
import org.stellar.anchor.config.CustodyConfig.CustodyType.NONE
import org.stellar.anchor.config.Sep31Config.PaymentType.STRICT_RECEIVE
import org.stellar.anchor.config.Sep31Config.PaymentType.STRICT_SEND
import org.stellar.anchor.custody.CustodyService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.sep31.Sep31Service.Context
import org.stellar.anchor.sep38.PojoSep38Quote
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.setupMock
import org.stellar.anchor.util.GsonUtils

@Order(13)
class Sep31ServiceTest {
  companion object {
    val gson: Gson = GsonUtils.getInstance()

    private const val stellarUSDC =
      "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"

    private const val stellarJPYC =
      "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

    private const val requestJson =
      """
        {
          "amount": "500",
          "asset_code": "USDC",
          "sender_id": "3f720570-26bd-4ea6-b0a2-1643ef4149da",
          "receiver_id": "69401a9a-7edb-4121-b507-1089a4389b11",
          "fields": {
            "transaction": {
              "receiver_account_number": "1",
              "type": "SWIFT",
              "receiver_routing_number": "1"
            }
          }
        }
    """

    private const val feeJson =
      """
        {
          "amount": "2",
          "asset": "USDC"
        }
    """

    private const val assetJson =
      """
            {
              "id": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
              "distribution_account": "GA7FYRB5VREZKOBIIKHG5AVTPFGWUBPOBF7LTYG4GTMFVIOOD2DWAL7I",
              "significant_decimals": 2,
              "sep31": {
                "enabled": true,
                "receive": {
                  "min_amount": 1,
                  "max_amount": 1000000,
                  "methods": [
                    "SEPA",
                    "SWIFT"
                  ]
                },
                "quotes_supported": true,
                "quotes_required": true
              },
              "sep38": {
                "enabled": true,
                "exchangeable_assets": [
                  "iso4217:USD"
                ]
              }
            }

        """

    private const val txnJson =
      """
    {
      "id": "a2392add-87c9-42f0-a5c1-5f1728030b68",
      "status": "pending_sender",
      "statusEta": "100",
      "amountIn": "100",
      "amountInAsset": "USDC",
      "amountOut": "98",
      "amountOutAsset": "USD",
      "amountFee": "2",
      "amountFeeAsset": "USDC",
      "toAccount": "GAYR3FVW2PCXTNHHWHEAFOCKZQV4PEY2ZKGIKB47EKPJ3GSBYA52XJBY",
      "fromAccount": "GDQPA2BWGDZJAOQRLEVLV2BQGD5NCU4PXIBCVHGZL5ILUSZKAAPNLYZL",
      "stellarMemo": "123456",
      "stellarMemoType": "text",
      "startedAt": "2022-04-18T14:00:00.000Z",
      "transferReceivedAt": "2022-04-18T14:30:00.000Z",
      "updatedAt": "2022-04-18T15:00:00.000Z",
      "completedAt": "2022-04-18T15:00:00.000Z",
      "stellarTransactionId": "18db1b8dffa78a0567faadeab7b08b7be8b3f65c40018d609cc530b757e67bc2",
      "externalTransactionId": "external-id",
      "requiredInfoMessage": "Don't forget to foo bar",
      "quoteId": "quote_id",
      "clientDomain": "demo-wallet-server.stellar.org",
      "requiredInfoUpdates" : {
        "transaction" : {
          "type": {
            "description": "type of deposit to make",
            "choices": [
              "SEPA",
              "SWIFT"
            ],
            "optional": false
          }
        }
      },
      "fields": {
        "receiver_account_number": "1",
        "type": "SWIFT",
        "receiver_routing_number": "1"
      },
      "refunded": true,
      "refunds": {
        "amountRefunded": "90",
        "amountFee": "8",
        "refundPayments": [
          {
            "id": "111",
            "amount": "50",
            "fee": 4
          },
          {
            "id": "222",
            "amount": "40",
            "fee": 4
          }
        ]
      },
      "stellarTransactions": [],
      "amountExpected": "100",
      "receiverId": "6820d44d-0881-4c94-aa55-1f4166b912f0",
      "senderId": "3e3fa1f8-f24f-4be0-aab9-407b17753624",
      "creator": {
        "id": "5d35ef3f-9b80-457d-90e5-ad888536c6b9"
      }
    }
    """

    private const val quoteJson =
      """
      {
        "id": "de762cda-a193-4961-861e-57b31fed6eb3",
        "expires_at": "2021-04-30T07:42:23",
        "total_price": "0.008",
        "price": "0.0072",
        "sell_asset": "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
        "sell_amount": "100",
        "buy_asset": "stellar:JPYC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
        "buy_amount": "12500",
        "fee": {
          "total": "10.00",
          "asset": "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
        }
      }
  """
    private const val patchTxnRequestJson =
      """
      {
        "id": "a2392add-87c9-42f0-a5c1-5f1728030b68",
        "fields": {
          "transaction": {
            "type": "SEPA"
          }
        }
      }
  """

    private val custodialClient =
      CustodialClient.builder()
        .name("custodialClient")
        .signingKeys(setOf("GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEG"))
        .callbackUrls(
          CallbackUrls.builder().sep31("http://wallet-server:8092/callbacks/sep31").build()
        )
        .allowAnyDestination(false)
        .destinationAccounts(emptySet())
        .build()

    @JvmStatic
    fun generateGetClientNameTestConfig(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(listOf<String>(), false, null, false),
        Arguments.of(listOf<String>(), true, null, true),
        Arguments.of(listOf(custodialClient.name), false, custodialClient.name, false),
        Arguments.of(listOf(custodialClient.name), true, custodialClient.name, true),
      )
    }
  }

  private val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")

  @MockK(relaxed = true) private lateinit var txnStore: Sep31TransactionStore

  @MockK(relaxed = true) lateinit var appConfig: AppConfig
  @MockK(relaxed = true) lateinit var secretConfig: SecretConfig
  @MockK(relaxed = true) lateinit var custodySecretConfig: CustodySecretConfig
  @MockK(relaxed = true) lateinit var clientService: ClientService
  @MockK(relaxed = true) lateinit var sep10Config: Sep10Config
  @MockK(relaxed = true) lateinit var sep31Config: Sep31Config
  @MockK(relaxed = true) lateinit var sep31DepositInfoGenerator: Sep31DepositInfoGenerator
  @MockK(relaxed = true) lateinit var quoteStore: Sep38QuoteStore
  @MockK(relaxed = true) lateinit var rateIntegration: RateIntegration
  @MockK(relaxed = true) lateinit var customerIntegration: CustomerIntegration
  @MockK(relaxed = true) lateinit var custodyService: CustodyService
  @MockK(relaxed = true) lateinit var custodyConfig: CustodyConfig
  @MockK(relaxed = true) lateinit var eventService: EventService
  @MockK(relaxed = true) lateinit var eventSession: Session

  private lateinit var jwtService: JwtService
  private lateinit var sep31Service: Sep31Service
  private lateinit var request: Sep31PostTransactionRequest
  private lateinit var txn: Sep31Transaction
  private lateinit var fee: Amount
  private lateinit var asset: AssetInfo
  private lateinit var quote: PojoSep38Quote
  private lateinit var patchRequest: Sep31PatchTransactionRequest

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    secretConfig.setupMock()
    every { appConfig.languages } returns listOf("en")
    every { sep31Config.paymentType } returns STRICT_SEND
    every { txnStore.newTransaction() } returns PojoSep31Transaction()
    every { custodyConfig.type } returns NONE
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession

    jwtService = spyk(JwtService(secretConfig, custodySecretConfig))

    sep31Service =
      Sep31Service(
        appConfig,
        sep10Config,
        sep31Config,
        txnStore,
        quoteStore,
        clientService,
        assetService,
        rateIntegration,
        eventService,
      )

    request = gson.fromJson(requestJson, Sep31PostTransactionRequest::class.java)
    txn = gson.fromJson(txnJson, PojoSep31Transaction::class.java)
    fee = gson.fromJson(feeJson, Amount::class.java)
    asset = gson.fromJson(assetJson, StellarAssetInfo::class.java)
    quote = gson.fromJson(quoteJson, PojoSep38Quote::class.java)
    patchRequest = gson.fromJson(patchTxnRequestJson, Sep31PatchTransactionRequest::class.java)
  }

  @Test
  fun `test update transaction amounts when no quote was used`() {
    request.destinationAsset =
      "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    Context.get().transaction = txn
    Context.get().request = request
    Context.get().fee = fee
    Context.get().asset = asset
    every { sep31Config.paymentType } returns STRICT_SEND

    request.amount = "100"
    fee.amount = "2"
    sep31Service.updateTxAmountsWhenNoQuoteWasUsed()
    assertEquals(txn.amountIn, "100")
    assertEquals(txn.amountOut, "98")

    every { sep31Config.paymentType } returns STRICT_RECEIVE
    sep31Service.updateTxAmountsWhenNoQuoteWasUsed()
    assertEquals("102", txn.amountIn)
    assertEquals("100", txn.amountOut)
  }

  @Test
  fun `test quotes supported and required validation`() {
    val ex: AnchorException = assertThrows {
      DefaultAssetService.fromJsonResource(
        "test_assets.json.quotes_required_but_not_supported",
      )
    }
    assertInstanceOf(InvalidConfigException::class.java, ex)
    assertEquals(
      "Asset " +
        asset.id +
        ": SEP-31 'quotes_supported' must be true if 'quotes_required' is true.",
      ex.message,
    )
  }

  @Test
  fun `test update tx amounts based on quote`() {
    Context.get().transaction = txn
    Context.get().request = request
    Context.get().fee = fee
    Context.get().quote = quote

    // Fee is as sell asset
    every { sep31Config.paymentType } throws Exception("paymentType must not be called")
    request.quoteId = "quote_id"
    request.amount = "100"
    request.assetCode = "USDC"
    sep31Service.updateTxAmountsBasedOnQuote()

    // TODO: Add fee validation.
    assertEquals("100", txn.amountIn)
    assertEquals(
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
      txn.amountInAsset,
    )
    assertEquals("12500", txn.amountOut)
    assertEquals(stellarJPYC, txn.amountOutAsset)

    // Test null quote failure
    Context.get().quote = null
    val ex = assertThrows<ServerErrorException> { sep31Service.updateTxAmountsBasedOnQuote() }
    assertEquals("Quote not found.", ex.message)
  }

  @Test
  fun `test GET transaction`() {
    assertThrows<BadRequestException> { sep31Service.getTransaction(null) }
    assertThrows<BadRequestException> { sep31Service.getTransaction("") }

    every { txnStore.findByTransactionId("not_found") } returns null
    val ex = assertThrows<NotFoundException> { sep31Service.getTransaction("not_found") }
    assertEquals("transaction (id=not_found) not found", ex.message)

    every { txnStore.findByTransactionId("found") } returns txn
    val gotTxResponse = sep31Service.getTransaction("found")

    val wantStartedAt =
      DateTimeFormatter.ISO_INSTANT.parse("2022-04-18T14:00:00.000Z", Instant::from)
    val wantCompletedAt =
      DateTimeFormatter.ISO_INSTANT.parse("2022-04-18T15:00:00.000Z", Instant::from)
    val wantRefunds =
      Sep31GetTransactionResponse.Refunds.builder()
        .amountRefunded("90")
        .amountFee("8")
        .payments(
          listOf(
            Sep31GetTransactionResponse.Sep31RefundPayment.builder()
              .id("111")
              .amount("50")
              .fee("4")
              .build(),
            Sep31GetTransactionResponse.Sep31RefundPayment.builder()
              .id("222")
              .amount("40")
              .fee("4")
              .build(),
          )
        )
        .build()

    val wantRequiredInfoUpdates = Sep31Info.Fields()
    wantRequiredInfoUpdates.transaction =
      mapOf("type" to Field("type of deposit to make", listOf("SEPA", "SWIFT"), false))

    val wantTxResponse =
      Sep31GetTransactionResponse(
        Sep31GetTransactionResponse.TransactionResponse.builder()
          .id("a2392add-87c9-42f0-a5c1-5f1728030b68")
          .status("pending_sender")
          .statusEta(100)
          .amountIn("100")
          .amountInAsset("USDC")
          .amountOut("98")
          .amountOutAsset("USD")
          .feeDetails(FeeDetails("2", "USDC"))
          .quoteId("quote_id")
          .stellarAccountId("GAYR3FVW2PCXTNHHWHEAFOCKZQV4PEY2ZKGIKB47EKPJ3GSBYA52XJBY")
          .stellarMemo("123456")
          .stellarMemoType("text")
          .startedAt(wantStartedAt)
          .completedAt(wantCompletedAt)
          .stellarTransactionId("18db1b8dffa78a0567faadeab7b08b7be8b3f65c40018d609cc530b757e67bc2")
          .externalTransactionId("external-id")
          .refunded(true)
          .refunds(wantRefunds)
          .requiredInfoMessage("Don't forget to foo bar")
          .requiredInfoUpdates(wantRequiredInfoUpdates)
          .build()
      )

    assertEquals(wantTxResponse, gotTxResponse)
  }

  @Test
  fun `test PATCH transaction ok`() {
    txn.status = "pending_transaction_info_update"
    every { txnStore.findByTransactionId("a2392add-87c9-42f0-a5c1-5f1728030b68") } returns txn
    sep31Service.patchTransaction(patchRequest)
    // TODO: Add more saved transaction field validation
  }

  @Test
  fun `test PATCH transaction failure`() {
    val ex1 = assertThrows<BadRequestException> { sep31Service.patchTransaction(null) }
    assertEquals("request cannot be null", ex1.message)

    val ex2 =
      assertThrows<BadRequestException> {
        sep31Service.patchTransaction(Sep31PatchTransactionRequest.builder().build())
      }
    assertEquals("id cannot be null nor empty", ex2.message)

    every { txnStore.findByTransactionId(any()) } returns null
    val ex3 = assertThrows<NotFoundException> { sep31Service.patchTransaction(patchRequest) }
    assertEquals("transaction (id=${patchRequest.id}) not found", ex3.message)

    patchRequest.fields.transaction.clear()
    patchRequest.fields.transaction["unexpected_field"] = "unexpected_field_value"
    txn.status = "pending_transaction_info_update"
    every { txnStore.findByTransactionId(any()) } returns txn
    val ex4 = assertThrows<BadRequestException> { sep31Service.patchTransaction(patchRequest) }
    assertEquals("[unexpected_field] is not a expected field", ex4.message)

    txn.requiredInfoUpdates = null
    every { txnStore.findByTransactionId(any()) } returns txn
    val ex5 = assertThrows<BadRequestException> { sep31Service.patchTransaction(patchRequest) }
    assertEquals("Transaction (${txn.id}) is not expecting any updates", ex5.message)

    txn.status = "completed"
    every { txnStore.findByTransactionId(any()) } returns txn
    val ex6 = assertThrows<BadRequestException> { sep31Service.patchTransaction(patchRequest) }
    assertEquals("transaction (id=${txn.id}) does not need update", ex6.message)
  }

  @Test
  fun `test POST transaction failures`() {
    val jwtToken = TestHelper.createSep10Jwt()

    // missing asset code
    val postTxRequest = Sep31PostTransactionRequest()
    var ex: AnchorException = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("asset null:null is not supported.", ex.message)

    // invalid asset code
    postTxRequest.assetCode = "FOO"
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("asset FOO:null is not supported.", ex.message)

    // invalid asset issuer
    postTxRequest.assetCode = "USDC"
    postTxRequest.assetIssuer = "BAR"
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("asset USDC:BAR is not supported.", ex.message)

    // missing amount
    postTxRequest.assetIssuer = null
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount cannot be empty", ex.message)

    // invalid amount (not a number)
    postTxRequest.amount = "FOO BAR"
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount is invalid", ex.message)

    // invalid amount (not a positive number)
    postTxRequest.amount = "-0.01"
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount should be positive", ex.message)

    // invalid amount (not a positive number)
    postTxRequest.amount = "0"
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount should be positive", ex.message)

    postTxRequest.lang = "en"
    postTxRequest.amount = "1"
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("funding_method cannot be empty", ex.message)

    postTxRequest.fundingMethod = "SEPA"
    // ----- QUOTE_ID IS USED ⬇️ -----
    // not found quote_id
    val fields =
      hashMapOf(
        "receiver_account_number" to "1",
        "type" to "1",
        "receiver_routing_number" to "SWIFT",
      )
    postTxRequest.fields = Sep31TxnFields(fields)
    postTxRequest.quoteId = "not-found-quote-id"
    every { quoteStore.findByQuoteId(any()) } returns null
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("quote(id=not-found-quote-id) was not found.", ex.message)

    // quote and tx amounts don't match
    val quoteId = "de762cda-a193-4961-861e-57b31fed6eb3"
    val quote = PojoSep38Quote()
    quote.sellAmount = "100.1"
    postTxRequest.amount = "100"
    postTxRequest.quoteId = quoteId
    every { quoteStore.findByQuoteId(quoteId) } returns quote

    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals(
      "Quote sell amount [100.1] is different from the SEP-31 transaction amount [100]",
      ex.message,
    )

    // quote and tx assets don't match (quote.sell_asset is null)
    quote.sellAmount = "100.00000"
    every { quoteStore.findByQuoteId(quoteId) } returns quote
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals(
      "Quote sell asset [null] is different from the SEP-31 transaction asset [stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP]",
      ex.message,
    )

    // quote and tx assets don't match
    quote.sellAsset = "stellar:USDC:zzz"
    every { quoteStore.findByQuoteId(quoteId) } returns quote
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals(
      "Quote sell asset [stellar:USDC:zzz] is different from the SEP-31 transaction asset [stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP]",
      ex.message,
    )

    // quote is missing the `fee` field
    quote.sellAsset = stellarUSDC
    every { quoteStore.findByQuoteId(quoteId) } returns quote
    ex = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(SepValidationException::class.java, ex)
    assertEquals("Quote is missing the 'fee' field", ex.message)
  }

  @Test
  fun `test POST transaction with quote`() {
    val tomorrow = Instant.now().plus(1, ChronoUnit.DAYS)
    quote.expiresAt = tomorrow
    quote.id = "my_quote_id"
    quote.sellAsset = stellarUSDC
    quote.sellAmount = "100"
    quote.buyAsset = stellarJPYC
    quote.buyAmount = "12500"
    quote.totalPrice = "0.008"
    quote.price = "0.0072"
    quote.expiresAt = Instant.now()
    quote.fee = FeeDetails("10", stellarUSDC)
    every { quoteStore.findByQuoteId("my_quote_id") } returns quote

    val senderId = "d2bd1412-e2f6-4047-ad70-a1a2f133b25c"
    val receiverId = "137938d4-43a7-4252-a452-842adcee474c"
    val postTxRequest = Sep31PostTransactionRequest()
    postTxRequest.amount = "100"
    postTxRequest.assetCode = "USDC"
    postTxRequest.assetIssuer = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    postTxRequest.senderId = senderId
    postTxRequest.receiverId = receiverId
    postTxRequest.quoteId = "my_quote_id"
    postTxRequest.fundingMethod = "SEPA"
    postTxRequest.fields =
      Sep31TxnFields(
        hashMapOf(
          "receiver_account_number" to "1",
          "type" to "1",
          "receiver_routing_number" to "SWIFT",
        ),
      )

    // Make sure we can get the sender and receiver customers
    val mockCustomer = GetCustomerResponse()
    mockCustomer.status = Sep12Status.ACCEPTED.name
    every { customerIntegration.getCustomer(any()) } returns mockCustomer
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    // mock sep31 deposit info generation
    val txForDepositInfoGenerator = slot<Sep31Transaction>()
    every { sep31DepositInfoGenerator.generate(capture(txForDepositInfoGenerator)) } answers
      {
        val tx: Sep31Transaction = txForDepositInfoGenerator.captured
        var memo = StringUtils.truncate(tx.id, 32)
        memo = StringUtils.leftPad(memo, 32, '0')
        memo = String(Base64.getEncoder().encode(memo.toByteArray()))
        SepDepositInfo(tx.toAccount, memo, "hash")
      }

    // mock client config
    every { sep10Config.allowedClientNames } returns listOf("vibrant")
    every { clientService.getClientConfigBySigningKey(any()) } returns
      CustodialClient().apply { name = "vibrant" }

    // mock transaction save
    val slotTxn = slot<Sep31Transaction>()
    every { txnStore.save(capture(slotTxn)) } answers
      {
        firstArg<Sep31Transaction>().id = "ABC-123"
        firstArg()
      }

    // POST transaction
    val jwtToken = TestHelper.createSep10Jwt(accountMemo = TestHelper.TEST_MEMO)
    var gotResponse: Sep31PostTransactionResponse? = null
    assertDoesNotThrow { gotResponse = sep31Service.postTransaction(jwtToken, postTxRequest) }

    // verify if the mocks were called
    verify(exactly = 1) { quoteStore.findByQuoteId("my_quote_id") }
    verify(exactly = 1) { eventSession.publish(any()) }

    // validate the values of the saved sep31Transaction
    val gotTx = gson.toJson(slotTxn.captured)
    val txId = slotTxn.captured.id
    val txStartedAt = slotTxn.captured.startedAt
    val wantTx =
      """{
      "id": "$txId",
      "status": "pending_receiver",
      "fundingMethod": "SEPA",
      "amountFee": "10",
      "amountFeeAsset": "$stellarUSDC",
      "startedAt": "$txStartedAt",
      "updatedAt": "$txStartedAt",
      "quoteId": "my_quote_id",
      "clientDomain": "vibrant.stellar.org",
      "clientName": "vibrant",
      "fields": {
        "receiver_account_number": "1",
        "type": "1",
        "receiver_routing_number": "SWIFT"
      },
      "amountExpected": "100",
      "amountIn": "100",
      "amountInAsset": "$stellarUSDC",
      "amountOut": "12500",
      "amountOutAsset": "$stellarJPYC",
      "receiverId":"137938d4-43a7-4252-a452-842adcee474c",
      "senderId":"d2bd1412-e2f6-4047-ad70-a1a2f133b25c",
      "creator": {
        "account": "GBJDSMTMG4YBP27ZILV665XBISBBNRP62YB7WZA2IQX2HIPK7ABLF4C2",
        "memo": "123456"
      }
    }"""
        .trimMargin()
    JSONAssert.assertEquals(wantTx, gotTx, true)

    // validate the final response
    val wantResponse = Sep31PostTransactionResponse.builder().id(txId).build()
    assertEquals(wantResponse, gotResponse)
  }

  @Test
  fun `test POST transaction without quote and quote is required`() {
    Context.get().asset = asset
    val senderId = "d2bd1412-e2f6-4047-ad70-a1a2f133b25c"
    val receiverId = "137938d4-43a7-4252-a452-842adcee474c"
    val postTxRequest = Sep31PostTransactionRequest()
    postTxRequest.amount = "100"
    postTxRequest.assetCode = "USDC"
    postTxRequest.assetIssuer = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    postTxRequest.senderId = senderId
    postTxRequest.receiverId = receiverId
    postTxRequest.fundingMethod = "SEPA"
    postTxRequest.fields =
      Sep31TxnFields(
        hashMapOf(
          "receiver_account_number" to "1",
          "type" to "1",
          "receiver_routing_number" to "SWIFT",
        ),
      )

    // Make sure we can get the sender and receiver customers
    val mockCustomer = GetCustomerResponse()
    mockCustomer.status = Sep12Status.ACCEPTED.name
    every { customerIntegration.getCustomer(any()) } returns mockCustomer

    // POST transaction
    val jwtToken = TestHelper.createSep10Jwt()
    val ex: AnchorException = assertThrows { sep31Service.postTransaction(jwtToken, postTxRequest) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("quotes_required is set to true; quote id cannot be empty", ex.message)
  }

  @Test
  fun `test post transaction when quote is not supported`() {
    every { sep31DepositInfoGenerator.generate(any()) } returns
      SepDepositInfo("GA7FYRB5VREZKOBIIKHG5AVTPFGWUBPOBF7LTYG4GTMFVIOOD2DWAL7I", "123456", "id")

    every { txnStore.save(any()) } answers
      {
        firstArg<Sep31Transaction>().id = "ABC-123"
        firstArg()
      }

    val assetServiceQuotesNotSupported: AssetService =
      DefaultAssetService.fromJsonResource(
        "test_assets.json.quotes_not_supported",
      )
    sep31Service =
      Sep31Service(
        appConfig,
        sep10Config,
        sep31Config,
        txnStore,
        quoteStore,
        clientService,
        assetServiceQuotesNotSupported,
        rateIntegration,
        eventService
      )

    val senderId = "d2bd1412-e2f6-4047-ad70-a1a2f133b25c"
    val receiverId = "137938d4-43a7-4252-a452-842adcee474c"
    val postTxRequest = Sep31PostTransactionRequest()
    postTxRequest.amount = "100"
    postTxRequest.assetCode = "USDC"
    postTxRequest.assetIssuer = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    postTxRequest.senderId = senderId
    postTxRequest.receiverId = receiverId
    postTxRequest.fundingMethod = "SEPA"
    postTxRequest.fields =
      Sep31TxnFields(
        hashMapOf(
          "receiver_account_number" to "1",
          "type" to "1",
          "receiver_routing_number" to "SWIFT",
        ),
      )

    // Provide fee response.
    every { rateIntegration.getRate(any()) } returns
      GetRateResponse(GetRateResponse.Rate.builder().fee(FeeDetails("2", "stellar:USDC")).build())

    // Make sure we can get the sender and receiver customers
    val mockCustomer = GetCustomerResponse()
    mockCustomer.status = Sep12Status.ACCEPTED.name
    every { customerIntegration.getCustomer(any()) } returns mockCustomer

    // POST transaction
    val jwtToken = TestHelper.createSep10Jwt()
    var gotResponse: Sep31PostTransactionResponse? = null
    assertDoesNotThrow { gotResponse = sep31Service.postTransaction(jwtToken, postTxRequest) }

    val wantResponse = Sep31PostTransactionResponse.builder().id(gotResponse!!.id).build()
    assertEquals(wantResponse, gotResponse)
  }

  private val jpycJson =
    """
      {"enabled":true,"quotes_supported":true,"quotes_required":true,"min_amount":1,"max_amount":1000000,"funding_methods":["SEPA","SWIFT"]}
    """
      .trimIndent()

  private val usdcJson =
    """
    {"enabled":true,"quotes_supported":true,"quotes_required":true,"min_amount":1,"max_amount":1000000,"funding_methods":["SEPA","SWIFT"]}
  """
      .trimIndent()

  @Test
  fun `test INFO response`() {
    val info = sep31Service.info
    val gotJpyc = info.receive["JPYC"]!!
    val gotUsdc = info.receive["USDC"]!!

    val wantJpyc = gson.fromJson(jpycJson, Sep31InfoResponse.AssetResponse::class.java)
    val wantUsdc = gson.fromJson(usdcJson, Sep31InfoResponse.AssetResponse::class.java)

    assertEquals(wantJpyc, gotJpyc)
    assertEquals(wantUsdc, gotUsdc)
  }

  @Test
  fun `test validate required fields`() {
    Context.reset()
    val ex1 = assertThrows<BadRequestException> { sep31Service.validateRequiredFields() }
    assertEquals("Missing asset information.", ex1.message)

    val assetInfo = assetService.getAsset("USDC") as StellarAssetInfo
    val originalId = assetInfo.id
    Context.get().asset = assetInfo
    assetInfo.id = "stellar:BAD:issuer"
    val ex2 = assertThrows<BadRequestException> { sep31Service.validateRequiredFields() }
    assertEquals("Asset [BAD] has no fields definition", ex2.message)

    assetInfo.id = originalId
    val ex3 = assertThrows<BadRequestException> { sep31Service.validateRequiredFields() }
    assertEquals("'fields' field must have one 'transaction' field", ex3.message)
  }

  @Test
  fun `Test update fee ok`() {
    val jwtToken = TestHelper.createSep10Jwt()
    Context.get().request = request
    Context.get().sep10Jwt = jwtToken

    // With quote
    Context.get().quote = quote
    request.destinationAsset = "USDC"
    sep31Service.updateFee()
    var fee = Context.get().fee
    assertEquals(quote.fee.total, fee.amount)
    assertEquals(quote.fee.asset, fee.asset)

    // No quote
    every { rateIntegration.getRate(any()) } returns
      GetRateResponse(
        GetRateResponse.Rate.builder()
          .fee(
            FeeDetails(
              "10",
              "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
            )
          )
          .build()
      )
    Context.get().quote = null
    Context.get().asset = asset
    request.destinationAsset = "USDC"
    sep31Service.updateFee()
    fee = Context.get().fee
    assertEquals("10", fee.amount)
    assertEquals("stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN", fee.asset)

    request.destinationAsset = null
    sep31Service.updateFee()
    fee = Context.get().fee
    assertEquals("10", fee.amount)
    assertEquals("stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN", fee.asset)
  }

  @Test
  fun `test update fee failure`() {
    val jwtToken = TestHelper.createSep10Jwt()
    Context.get().request = request
    Context.get().sep10Jwt = jwtToken

    // With quote
    Context.get().quote = quote
    quote.fee = null
    val ex = assertThrows<SepValidationException> { sep31Service.updateFee() }
    assertEquals("Quote is missing the 'fee' field", ex.message)
  }

  @ParameterizedTest
  @MethodSource("generateGetClientNameTestConfig")
  fun `test getClientName when`(
    allowedClientNames: List<String>,
    isClientAttributionRequired: Boolean,
    expectedClientName: String?,
    shouldThrowExceptionWithInvalidInput: Boolean,
  ) {
    every { sep10Config.allowedClientNames } returns allowedClientNames
    every { sep10Config.isClientAttributionRequired } returns isClientAttributionRequired
    every { clientService.getClientConfigBySigningKey(custodialClient.signingKeys.first()) } returns
      custodialClient

    // client name should be returned for valid input
    val clientName = sep31Service.getClientName(custodialClient.signingKeys.first())
    assertEquals(expectedClientName, clientName)

    // exception maybe thrown for invalid input
    every { clientService.getClientConfigBySigningKey("Invalid Public Key") } returns null
    if (!shouldThrowExceptionWithInvalidInput) {
      val clientNameNotFound = sep31Service.getClientName("Invalid Public Key")
      assertNull(clientNameNotFound)
    } else {
      assertThrows<BadRequestException> { sep31Service.getClientName("Invalid Public Key") }
    }
  }
}
