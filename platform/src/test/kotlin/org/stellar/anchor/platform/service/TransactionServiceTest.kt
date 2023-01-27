package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode.LENIENT
import org.stellar.anchor.api.exception.AnchorException
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.api.platform.PatchTransactionRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.sep38.RateFee
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.platform.data.JdbcSep31RefundPayment
import org.stellar.anchor.platform.data.JdbcSep31Refunds
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep38Quote
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.util.GsonUtils

@Suppress("unused")
class TransactionServiceTest {
  companion object {
    private const val fiatUSD = "iso4217:USD"
    private const val stellarUSDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val TEST_ACCOUNT = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
    private const val TEST_MEMO = "test memo"
    private const val TEST_TXN_ID = "a4baff5f-778c-43d6-bbef-3e9fb41d096e"
    private val gson = GsonUtils.getInstance()
  }

  @MockK(relaxed = true) private lateinit var sep38QuoteStore: Sep38QuoteStore
  @MockK(relaxed = true) private lateinit var sep31TransactionStore: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var sep24TransactionStore: Sep24TransactionStore
  @MockK(relaxed = true) private lateinit var assetService: AssetService
  @MockK(relaxed = true) private lateinit var eventService: EventService
  private lateinit var transactionService: TransactionService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    transactionService =
      TransactionService(
        sep24TransactionStore,
        sep31TransactionStore,
        sep38QuoteStore,
        assetService,
        eventService
      )
  }

  @AfterEach
  fun tearDown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun test_getTransaction_failure() {
    // null tx id is rejected with 400
    var ex: AnchorException = assertThrows { transactionService.getTransactionResponse(null) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("transaction id cannot be empty", ex.message)

    // empty tx id is rejected with 400
    ex = assertThrows { transactionService.getTransactionResponse("") }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("transaction id cannot be empty", ex.message)

    // non-existent transaction is rejected with 404
    every { sep31TransactionStore.findByTransactionId(any()) } returns null
    every { sep24TransactionStore.findByTransactionId(any()) } returns null
    ex = assertThrows { transactionService.getTransactionResponse("not-found-tx-id") }
    assertInstanceOf(NotFoundException::class.java, ex)
    assertEquals("transaction (id=not-found-tx-id) is not found", ex.message)
  }

  @Test
  fun test_getTransaction() {
    // Mock the store
    every { sep31TransactionStore.newTransaction() } returns JdbcSep31Transaction()
    every { sep31TransactionStore.newRefunds() } returns JdbcSep31Refunds()
    every { sep31TransactionStore.newRefundPayment() } answers { JdbcSep31RefundPayment() }

    val mockSep31Transaction = gson.fromJson(jsonSep31Transaction, JdbcSep31Transaction::class.java)

    every { sep31TransactionStore.findByTransactionId(TEST_TXN_ID) } returns mockSep31Transaction
    val gotGetTransactionResponse = transactionService.getTransactionResponse(TEST_TXN_ID)

    JSONAssert.assertEquals(
      wantedGetTransactionResponse,
      gson.toJson(gotGetTransactionResponse),
      LENIENT
    )
  }

  @Test
  fun test_validateAsset_failure() {
    // fails if amount_in.amount is null
    var assetAmount = Amount(null, null)
    var ex =
      assertThrows<AnchorException> { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.amount cannot be empty", ex.message)

    // fails if amount_in.amount is empty
    assetAmount = Amount("", null)
    ex = assertThrows { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.amount cannot be empty", ex.message)

    // fails if amount_in.amount is invalid
    assetAmount = Amount("abc", null)
    ex = assertThrows { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.amount is invalid", ex.message)

    // fails if amount_in.amount is negative
    assetAmount = Amount("-1", null)
    ex = assertThrows { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.amount should be positive", ex.message)

    // fails if amount_in.amount is zero
    assetAmount = Amount("0", null)
    ex = assertThrows { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.amount should be positive", ex.message)

    // fails if amount_in.asset is null
    assetAmount = Amount("10", null)
    ex = assertThrows { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.asset cannot be empty", ex.message)

    // fails if amount_in.asset is empty
    assetAmount = Amount("10", "")
    ex = assertThrows { transactionService.validateAsset("amount_in", assetAmount) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("amount_in.asset cannot be empty", ex.message)

    // fails if listAllAssets is empty
    every { assetService.listAllAssets() } returns listOf()
    val mockAsset = Amount("10", fiatUSD)
    ex = assertThrows { transactionService.validateAsset("amount_in", mockAsset) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("'$fiatUSD' is not a supported asset.", ex.message)

    // fails if listAllAssets does not contain the desired asset
    this.assetService = DefaultAssetService.fromResource("test_assets.json")
    ex = assertThrows { transactionService.validateAsset("amount_in", mockAsset) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("'$fiatUSD' is not a supported asset.", ex.message)
  }

  @Test
  fun test_validateAsset() {
    this.assetService = DefaultAssetService.fromResource("test_assets.json")
    transactionService =
      TransactionService(
        sep24TransactionStore,
        sep31TransactionStore,
        sep38QuoteStore,
        assetService,
        eventService
      )
    val mockAsset = Amount("10", fiatUSD)
    assertDoesNotThrow { transactionService.validateAsset("amount_in", mockAsset) }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = ["pending_anchors", "null", "bad_status"])
  fun test_validateIfStatusIsSupported_failure(status: String?) {
    val ex: Exception = assertThrows { transactionService.validateIfStatusIsSupported(status) }
    assertInstanceOf(BadRequestException::class.java, ex)
    assertEquals("invalid status(${status})", ex.message)
  }

  @ParameterizedTest
  @EnumSource(
    value = SepTransactionStatus::class,
    mode = EnumSource.Mode.INCLUDE,
    names =
      [
        "PENDING_STELLAR",
        "PENDING_CUSTOMER_INFO_UPDATE",
        "PENDING_RECEIVER",
        "PENDING_EXTERNAL",
        "COMPLETED",
        "EXPIRED",
        "ERROR"
      ]
  )
  fun test_validateIfStatusIsSupported(sepTxnStatus: SepTransactionStatus) {
    assertDoesNotThrow { transactionService.validateIfStatusIsSupported(sepTxnStatus.getStatus()) }
  }

  @Test
  fun test_updateSep31Transaction() {
    val quoteId = "my-quote-id"
    val gson = GsonUtils.getInstance()

    // mock times
    val testPatchTransactionRequest =
      gson.fromJson(
        """
          {
            "transaction": {
              "id": "my-tx-id",
              "status": "completed",
              "amount_in": {
                "amount": "100",
                "asset": "iso4217:USD"
              },
              "amount_out": {
                "amount": "98",
                "asset": "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
              },
              "amount_fee": {
                "amount": "2",
                "asset": "iso4217:USD"
              },
              "updated_at": "2023-01-19T01:51:57.648850500Z",
              "started_at": "2023-01-19T01:51:57.648850500Z",
              "transfer_received_at": "2023-01-19T01:59:37.330684800Z",
              "message": "Remittance was successfully completed.",
              "refunds": {
                "amount_refunded": {
                  "amount": "90.0000",
                  "asset": "iso4217:USD"
                },
                "amount_fee": {
                  "amount": "8.0000",
                  "asset": "iso4217:USD"
                },
                "payments": [
                  {
                    "id": "1111",
                    "id_type": "stellar",
                    "amount": {
                      "amount": "50.0000",
                      "asset": "iso4217:USD"
                    },
                    "fee": {
                      "amount": "4.0000",
                      "asset": "iso4217:USD"
                    }
                  },
                  {
                    "id": "2222",
                    "id_type": "stellar",
                    "amount": {
                      "amount": "40.0000",
                      "asset": "iso4217:USD"
                    },
                    "fee": {
                      "amount": "4.0000",
                      "asset": "iso4217:USD"
                    }
                  }
                ]
              },
              "external_transaction_id": "external-id"
            }
          }      
      """
          .trimIndent(),
        PatchTransactionRequest::class.java
      )

    val testSep31Transaction =
      gson.fromJson(
        """
              {
                "id": "my-tx-id",
                "quote_id": "my-quote-id",
                "updated_at": "2023-01-19T01:51:47.648850500Z",
                "started_at": "2023-01-19T01:51:47.648850500Z",
                "transfer_received_at": "2023-01-19T01:51:47.648850500Z"
              }          
            """,
        JdbcSep31Transaction::class.java
      )

    val testSep38Quote = JdbcSep38Quote()
    testSep38Quote.id = "my-quote-id"
    testSep38Quote.sellAmount = "100"
    testSep38Quote.sellAsset = fiatUSD
    testSep38Quote.buyAmount = "98"
    testSep38Quote.buyAsset = stellarUSDC
    testSep38Quote.fee = RateFee("2", fiatUSD)

    every { sep38QuoteStore.findByQuoteId(quoteId) } returns testSep38Quote

    println(gson.toJson(testSep38Quote))

    this.assetService = DefaultAssetService.fromResource("test_assets.json")
    transactionService =
      TransactionService(
        sep24TransactionStore,
        sep31TransactionStore,
        sep38QuoteStore,
        assetService,
        eventService
      )

    assertDoesNotThrow {
      transactionService.updateSepTransaction(
        testPatchTransactionRequest.transaction,
        testSep31Transaction
      )
    }

    val expectSep31Transaction =
      gson.fromJson(
        """
        {
          "id": "my-tx-id",
          "quote_id": "my-quote-id",
          "refunds": {
            "amount_refunded": "",
            "amount_fee": "",
            "payments": []
          },
          "status": "completed",
          "amount_in": "100",
          "amount_in_asset": "iso4217:USD",
          "amount_out": "98",
          "amount_out_asset": "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
          "amount_fee": "2",
          "amount_fee_asset": "iso4217:USD",
          "started_at": "2023-01-19T01:51:57.648850500Z",
          "transfer_received_at": "2023-01-19T01:59:37.330684800Z",
          "external_transaction_id": "external-id",
          "required_info_message": "Remittance was successfully completed."
        }
        
      """
          .trimIndent(),
        JdbcSep31Transaction::class.java
      )

    JSONAssert.assertEquals(
      gson.toJson(expectSep31Transaction),
      gson.toJson(testSep31Transaction),
      LENIENT
    )

    assertTrue(testSep31Transaction.updatedAt > testSep31Transaction.startedAt)
  }

  val jsonSep31Transaction =
    """
    {
      "id": "a4baff5f-778c-43d6-bbef-3e9fb41d096e",
      "status": "pending_receiver",
      "status_eta": 120,
      "amount_in": "100.0000",
      "amount_in_asset": "iso4217:USD",
      "amount_out": "98.0000000",
      "amount_out_asset": "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
      "amount_fee": "2.0000",
      "amount_fee_asset": "iso4217:USD",
      "stellar_account_id": "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR",
      "stellar_memo": "test memo",
      "stellar_memo_type": "text",
      "started_at": "2022-12-19T02:06:44.500182800Z",
      "completed_at": "2022-12-19T02:09:44.500182800Z",
      "stellar_transaction_id": "2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300",
      "stellarTransactions": [
        {
          "id": "2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300",
          "memo": "my-memo",
          "memo_type": "text",
          "created_at": "2022-12-19T02:08:44.500182800Z",
          "envelope": "here_comes_the_envelope",
          "payments": [
            {
              "id": "4609238642995201",
              "amount": {
                "amount": "100.0000",
                "asset": "iso4217:USD"
              },
              "payment_type": "payment",
              "source_account": "GAS4OW4HKJCC2D6VWUHVFR3MJRRVQBXBFQ3LCZJXBR7TWOOBJWE4SRWZ",
              "destination_account": "GBQC7NCZMQIPWN6ASUJYIDKDPRK34IOIZNQE5WOHPQH536VMOMQVJTN7"
            }
          ]
        }
      ],
      "external_transaction_id": "external-tx-id",
      "required_info_message": "Please don\u0027t forget to foo bar",
      "quote_id": "quote-id",
      "client_domain": "test.com",
      "sender_id": "6c1770b0-0ea4-11ed-861d-0242ac120002",
      "receiver_id": "31212353-f265-4dba-9eb4-0bbeda3ba7f2",
      "creator": {
        "id": "141ee445-f32c-4c38-9d25-f4475d6c5558"
      },
      "required_info_updates": {
        "transaction": {
          "receiver_account_number": {
            "description": "bank account number of the destination",
            "optional": false
          }
        }
      },
      "refunded": true,
      "refunds": {
        "amount_refunded": "90.0000",
        "amount_fee": "8.0000",
        "payments": [
          {
            "id": "1111",
            "amount": "50.0000",
            "fee": "4.0000"
          },
          {
            "id": "2222",
            "amount": "40.0000",
            "fee": "4.0000"
          }
        ]
      },
      "updatedAt": "2022-12-19T02:07:44.500182800Z",
      "transferReceivedAt": "2022-12-19T02:08:44.500182800Z",
      "amountExpected": "100"
    }
  """
      .trimIndent()

  val testPlatformtransactionData =
    """
    {
      "id": "a4baff5f-778c-43d6-bbef-3e9fb41d096e",
      "sep": "31",
      "kind": "receive",
      "status": "pending_receiver",
      "amount_expected": {
        "amount": "100",
        "asset": "iso4217:USD"
      },
      "amount_in": {
        "amount": "100.0000",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "98.0000000",
        "asset": "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
      },
      "amount_fee": {
        "amount": "2.0000",
        "asset": "iso4217:USD"
      },
      "quote_id": "quote-id",
      "message": "Please don\u0027t forget to foo bar",
      "refunds": {
        "amount_refunded": {
          "amount": "90.0000",
          "asset": "iso4217:USD"
        },
        "amount_fee": {
          "amount": "8.0000",
          "asset": "iso4217:USD"
        },
        "payments": [
          {
            "id": "1111",
            "id_type": "stellar",
            "amount": {
              "amount": "50.0000",
              "asset": "iso4217:USD"
            },
            "fee": {
              "amount": "4.0000",
              "asset": "iso4217:USD"
            }
          },
          {
            "id": "2222",
            "id_type": "stellar",
            "amount": {
              "amount": "40.0000",
              "asset": "iso4217:USD"
            },
            "fee": {
              "amount": "4.0000",
              "asset": "iso4217:USD"
            }
          }
        ]
      },
      "stellar_transactions": [
        {
          "id": "2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300",
          "memo": "my-memo",
          "memo_type": "text",
          "envelope": "here_comes_the_envelope",
          "payments": [
            {
              "id": "4609238642995201",
              "amount": {
                "amount": "100.0000",
                "asset": "iso4217:USD"
              },
              "payment_type": "payment",
              "source_account": "GAS4OW4HKJCC2D6VWUHVFR3MJRRVQBXBFQ3LCZJXBR7TWOOBJWE4SRWZ",
              "destination_account": "GBQC7NCZMQIPWN6ASUJYIDKDPRK34IOIZNQE5WOHPQH536VMOMQVJTN7"
            }
          ]
        }
      ],
      "external_transaction_id": "external-tx-id",
      "customers": {
        "sender": {
          "id": "6c1770b0-0ea4-11ed-861d-0242ac120002"
        },
        "receiver": {
          "id": "31212353-f265-4dba-9eb4-0bbeda3ba7f2"
        }
      },
      "creator": {
        "id": "141ee445-f32c-4c38-9d25-f4475d6c5558"
      }
    }

  """
      .trimIndent()

  val wantedGetTransactionResponse = testPlatformtransactionData
}
