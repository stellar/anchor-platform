@file:Suppress("unused")

package org.stellar.anchor.platform.service

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.platform.PatchTransactionsResponse
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep24TransactionStore
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep31TransactionStore
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSep6TransactionStore
import org.stellar.anchor.platform.observer.ObservedPayment
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.Asset
import org.stellar.sdk.Asset.create
import org.stellar.sdk.AssetTypeNative

class PaymentOperationToEventListenerTest {
  @MockK(relaxed = true) private lateinit var sep31TransactionStore: JdbcSep31TransactionStore
  @MockK(relaxed = true) private lateinit var sep24TransactionStore: JdbcSep24TransactionStore
  @MockK(relaxed = true) private lateinit var sep6TransactionStore: JdbcSep6TransactionStore
  @MockK(relaxed = true) private lateinit var platformApiClient: PlatformApiClient

  private lateinit var paymentOperationToEventListener: PaymentOperationToEventListener
  private val gson = GsonUtils.getInstance()

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    paymentOperationToEventListener =
      PaymentOperationToEventListener(
        sep31TransactionStore,
        sep24TransactionStore,
        sep6TransactionStore,
        platformApiClient
      )
  }

  @AfterEach
  fun tearDown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun test_onReceiver_failValidation() {
    // Payment missing txHash shouldn't trigger an event nor reach the DB
    val payment = ObservedPayment.builder().build()
    payment.transactionHash = null
    payment.transactionMemoType = "text"
    payment.transactionMemo = "my_memo_1"
    paymentOperationToEventListener.onReceived(payment)
    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }

    // Payment missing txMemo shouldn't trigger an event nor reach the DB
    payment.transactionHash = "1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30"
    payment.transactionMemo = null
    paymentOperationToEventListener.onReceived(payment)
    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }

    // Asset types different from "native", "credit_alphanum4" and "credit_alphanum12" shouldn't
    // trigger an event nor reach the DB
    payment.transactionHash = "1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30"
    payment.transactionMemo = "my_memo_1"
    payment.assetType = "liquidity_pool_shares"
    paymentOperationToEventListener.onReceived(payment)
    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }

    // Payment whose memo is not in the DB shouldn't trigger event
    payment.transactionHash = "1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30"
    payment.transactionMemo = "my_memo_2"
    payment.assetType = "credit_alphanum4"
    payment.sourceAccount = "GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5"
    payment.to = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(any(), any(), any())
    } returns null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every { sep6TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_2",
        "pending_sender"
      )
    }

    // If findByStellarAccountIdAndMemoAndStatus throws an exception, we shouldn't trigger an event
    payment.transactionMemo = "my_memo_3"
    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(any(), any(), any())
    } throws SepException("Something went wrong")
    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_sender"
      )
    }

    // If asset code from the fetched tx is different, don't trigger event
    payment.transactionMemo = "my_memo_4"
    payment.assetCode = "FOO"
    val sep31TxMock = JdbcSep31Transaction()
    sep31TxMock.amountInAsset = "BAR"
    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(any(), any(), any())
    } returns sep31TxMock
    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_4",
        "pending_sender"
      )
    }
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "native,native,,credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364,native,native,",
      ]
  )
  fun `test SEP-31 onReceived with sufficient payment patches the transaction`(
    inAssetType: String,
    inAssetCode: String,
    inAssetIssuer: String?,
    outAssetType: String,
    outAssetCode: String,
    outAssetIssuer: String?
  ) {
    val startedAtMock = Instant.now().minusSeconds(120)
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val inAsset = createAsset(inAssetType, inAssetCode, inAssetIssuer)
    val outAsset = createAsset(outAssetType, outAssetCode, outAssetIssuer)

    val payment =
      ObservedPayment.builder()
        .transactionHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .transactionMemo("39623738663066612d393366392d343139382d386439332d6537366664303834")
        .transactionMemoType("hash")
        .assetType(inAssetType)
        .assetCode(inAssetCode)
        .assetIssuer(inAssetIssuer)
        .assetName(inAsset.toString())
        .amount("10.0000000")
        .sourceAccount("GCJKWN7ELKOXLDHJTOU4TZOEJQL7TYVVTQFR676MPHHUIUDAHUA7QGJ4")
        .from("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
        .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .type(ObservedPayment.Type.PATH_PAYMENT)
        .createdAt(transferReceivedAtStr)
        .transactionEnvelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .id("755914248193")
        .build()

    val senderId = "d2bd1412-e2f6-4047-ad70-a1a2f133b25c"
    val receiverId = "137938d4-43a7-4252-a452-842adcee474c"

    val slotAccountId = slot<String>()
    val slotMemo = slot<String>()
    val slotStatus = slot<String>()
    val sep31TxMock = JdbcSep31Transaction()
    sep31TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep31TxMock.amountExpected = "10"
    sep31TxMock.amountIn = "10"
    sep31TxMock.amountInAsset = "stellar:$inAsset"
    sep31TxMock.amountOut = "20"
    sep31TxMock.amountOutAsset = "stellar:$outAsset"
    sep31TxMock.amountFee = "0.5"
    sep31TxMock.amountFeeAsset = "stellar:$inAsset"
    sep31TxMock.quoteId = "cef1fc13-3f65-4612-b1f2-502d698c816b"
    sep31TxMock.startedAt = startedAtMock
    sep31TxMock.updatedAt = startedAtMock
    sep31TxMock.transferReceivedAt = null // the event should have a valid `transferReceivedAt`
    sep31TxMock.stellarMemo = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ="
    sep31TxMock.stellarMemoType = "hash"
    sep31TxMock.status = SepTransactionStatus.PENDING_SENDER.status
    sep31TxMock.senderId = senderId
    sep31TxMock.receiverId = receiverId
    sep31TxMock.creator =
      StellarId.builder()
        .account("GBE4B7KE62NUBFLYT3BIG4OP5DAXBQX2GSZZOVAYXQKJKIU7P6V2R2N4")
        .build()

    val sep31TxCopy = gson.fromJson(gson.toJson(sep31TxMock), JdbcSep31Transaction::class.java)
    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        capture(slotAccountId),
        capture(slotMemo),
        capture(slotStatus)
      )
    } returns sep31TxCopy

    val patchTxnRequestSlot = slot<PatchTransactionsRequest>()
    every { platformApiClient.patchTransaction(capture(patchTxnRequestSlot)) } answers
      {
        PatchTransactionsResponse(emptyList())
      }

    val stellarTransaction =
      StellarTransaction.builder()
        .id("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .memo("OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=")
        .memoType("hash")
        .createdAt(transferReceivedAt)
        .envelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .payments(
          listOf(
            StellarPayment.builder()
              .id("755914248193")
              .paymentType(StellarPayment.Type.PATH_PAYMENT)
              .sourceAccount("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
              .destinationAccount("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
              .amount(Amount("10.0000000", inAsset.toString()))
              .build()
          )
        )
        .build()

    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_sender"
      )
    }

    val capturedRequest = patchTxnRequestSlot.captured.records[0]
    assertEquals(
      SepTransactionStatus.PENDING_RECEIVER.status,
      capturedRequest.transaction.status.toString()
    )
    assertEquals(stellarTransaction.id, capturedRequest.transaction.stellarTransactions[0].id)
    assertEquals(transferReceivedAt, capturedRequest.transaction.transferReceivedAt)
    assertEquals(transferReceivedAt, capturedRequest.transaction.updatedAt)
    assertEquals(listOf(stellarTransaction), capturedRequest.transaction.stellarTransactions)
  }

  @Test
  fun `test SEP-31 onReceived gets less than the expected amount it sends the PENDING_RECEIVER status with a message`() {
    val startedAtMock = Instant.now().minusSeconds(120)
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val fooAsset = "stellar:FOO:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val barAsset = "stellar:BAR:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"

    val payment =
      ObservedPayment.builder()
        .transactionHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .transactionMemo("39623738663066612d393366392d343139382d386439332d6537366664303834")
        .transactionMemoType("hash")
        .assetType("credit_alphanum4")
        .assetCode("FOO")
        .assetIssuer("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .assetName("FOO:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .amount("9.0000000")
        .sourceAccount("GCJKWN7ELKOXLDHJTOU4TZOEJQL7TYVVTQFR676MPHHUIUDAHUA7QGJ4")
        .from("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
        .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .type(ObservedPayment.Type.PAYMENT)
        .createdAt(transferReceivedAtStr)
        .transactionEnvelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .id("755914248193")
        .build()

    val senderId = "d2bd1412-e2f6-4047-ad70-a1a2f133b25c"
    val receiverId = "137938d4-43a7-4252-a452-842adcee474c"

    val slotMemo = slot<String>()
    val slotStatus = slot<String>()
    val sep31TxMock = JdbcSep31Transaction()
    sep31TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep31TxMock.amountExpected = "10"
    sep31TxMock.amountIn = "10"
    sep31TxMock.amountInAsset = fooAsset
    sep31TxMock.amountOut = "20"
    sep31TxMock.amountOutAsset = barAsset
    sep31TxMock.amountFee = "0.5"
    sep31TxMock.amountFeeAsset = fooAsset
    sep31TxMock.quoteId = "cef1fc13-3f65-4612-b1f2-502d698c816b"
    sep31TxMock.startedAt = startedAtMock
    sep31TxMock.updatedAt = startedAtMock
    sep31TxMock.transferReceivedAt = null // the event should have a valid `transferReceivedAt`
    sep31TxMock.stellarMemo = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ="
    sep31TxMock.stellarMemoType = "hash"
    sep31TxMock.status = SepTransactionStatus.PENDING_SENDER.name
    sep31TxMock.senderId = senderId
    sep31TxMock.receiverId = receiverId
    sep31TxMock.creator =
      StellarId.builder()
        .account("GBE4B7KE62NUBFLYT3BIG4OP5DAXBQX2GSZZOVAYXQKJKIU7P6V2R2N4")
        .build()

    val sep31TxCopy = gson.fromJson(gson.toJson(sep31TxMock), JdbcSep31Transaction::class.java)
    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus)
      )
    } returns sep31TxCopy

    val patchTxnRequestSlot = slot<PatchTransactionsRequest>()
    every { platformApiClient.patchTransaction(capture(patchTxnRequestSlot)) } answers
      {
        PatchTransactionsResponse(emptyList())
      }

    val stellarTransaction =
      StellarTransaction.builder()
        .id("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .memo("OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=")
        .memoType("hash")
        .createdAt(transferReceivedAt)
        .envelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .payments(
          listOf(
            StellarPayment.builder()
              .id("755914248193")
              .paymentType(StellarPayment.Type.PAYMENT)
              .sourceAccount("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
              .destinationAccount("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
              .amount(
                Amount("9.0000000", "FOO:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
              )
              .build()
          )
        )
        .build()

    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_sender"
      )
    }

    val capturedRequest = patchTxnRequestSlot.captured.records[0]
    assertEquals(
      SepTransactionStatus.PENDING_RECEIVER.status,
      capturedRequest.transaction.status.toString()
    )
    assertEquals(stellarTransaction.id, capturedRequest.transaction.stellarTransactions[0].id)
    assertEquals(null, capturedRequest.transaction.transferReceivedAt)
    assertEquals(transferReceivedAt, capturedRequest.transaction.updatedAt)
    assertEquals(listOf(stellarTransaction), capturedRequest.transaction.stellarTransactions)
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "native,native,",
        "credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      ]
  )
  fun `test SEP-24 onReceived with sufficient payment patches the transaction`(
    assetType: String,
    assetCode: String,
    assetIssuer: String?
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val payment =
      ObservedPayment.builder()
        .transactionHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .transactionMemo("39623738663066612d393366392d343139382d386439332d6537366664303834")
        .transactionMemoType("hash")
        .assetType(assetType)
        .assetCode(assetCode)
        .assetName(asset.toString())
        .assetIssuer(assetIssuer)
        .amount("10.0000000")
        .sourceAccount("GCJKWN7ELKOXLDHJTOU4TZOEJQL7TYVVTQFR676MPHHUIUDAHUA7QGJ4")
        .from("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
        .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .type(ObservedPayment.Type.PAYMENT)
        .createdAt(transferReceivedAtStr)
        .transactionEnvelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .id("755914248193")
        .build()

    val slotMemo = slot<String>()
    val slotStatus = slot<String>()
    val sep24TxMock = JdbcSep24Transaction()
    sep24TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep24TxMock.requestAssetCode = assetCode
    sep24TxMock.requestAssetIssuer = assetIssuer
    sep24TxMock.amountIn = "10.0000000"
    sep24TxMock.memo = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ"
    sep24TxMock.memoType = "hash"

    // TODO: this shouldn't be necessary
    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(any(), any(), any())
    } returns null
    every { sep6TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null

    val sep24TxnCopy = gson.fromJson(gson.toJson(sep24TxMock), JdbcSep24Transaction::class.java)
    every {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus)
      )
    } returns sep24TxnCopy

    val patchTxnRequestSlot = slot<PatchTransactionsRequest>()
    every { platformApiClient.patchTransaction(capture(patchTxnRequestSlot)) } answers
      {
        PatchTransactionsResponse(emptyList())
      }

    val stellarTransaction =
      StellarTransaction.builder()
        .id("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .memo("OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ")
        .memoType("hash")
        .createdAt(transferReceivedAt)
        .envelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .payments(
          listOf(
            StellarPayment.builder()
              .id("755914248193")
              .paymentType(StellarPayment.Type.PAYMENT)
              .sourceAccount("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
              .destinationAccount("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
              .amount(Amount("10.0000000", asset.toString()))
              .build()
          )
        )
        .build()

    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_user_transfer_start"
      )
    }

    val capturedRequest = patchTxnRequestSlot.captured.records[0]
    assertEquals(
      SepTransactionStatus.PENDING_ANCHOR.status.toString(),
      capturedRequest.transaction.status.toString()
    )
    assertEquals(stellarTransaction.id, capturedRequest.transaction.stellarTransactions[0].id)
    assertEquals(transferReceivedAt, capturedRequest.transaction.transferReceivedAt)
    assertEquals(transferReceivedAt, capturedRequest.transaction.updatedAt)
    assertEquals(listOf(stellarTransaction), capturedRequest.transaction.stellarTransactions)
    assertEquals(sep24TxMock.id, capturedRequest.transaction.id)
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "native,native,",
        "credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      ]
  )
  fun `test SEP-6 onReceived with sufficient payment patches the transaction`(
    assetType: String,
    assetCode: String,
    assetIssuer: String?
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val payment =
      ObservedPayment.builder()
        .transactionHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .transactionMemo("39623738663066612d393366392d343139382d386439332d6537366664303834")
        .transactionMemoType("hash")
        .assetType(assetType)
        .assetCode(assetCode)
        .assetName(asset.toString())
        .assetIssuer(assetIssuer)
        .amount("10.0000000")
        .sourceAccount("GCJKWN7ELKOXLDHJTOU4TZOEJQL7TYVVTQFR676MPHHUIUDAHUA7QGJ4")
        .from("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
        .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .type(ObservedPayment.Type.PAYMENT)
        .createdAt(transferReceivedAtStr)
        .transactionEnvelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .id("755914248193")
        .build()

    val slotMemo = slot<String>()
    val slotStatus = slot<String>()
    val sep6Txn = JdbcSep24Transaction()
    sep6Txn.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep6Txn.requestAssetCode = assetCode
    sep6Txn.requestAssetIssuer = assetIssuer
    sep6Txn.amountExpected = "10.0000000"
    sep6Txn.memo = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ"
    sep6Txn.memoType = "hash"

    every {
      sep31TransactionStore.findByStellarAccountIdAndMemoAndStatus(any(), any(), any())
    } returns null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null

    val sep6TxnCopy = gson.fromJson(gson.toJson(sep6Txn), JdbcSep6Transaction::class.java)
    every {
      sep6TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus)
      )
    } returns sep6TxnCopy

    val patchTxnRequestSlot = slot<PatchTransactionsRequest>()
    every { platformApiClient.patchTransaction(capture(patchTxnRequestSlot)) } answers
      {
        PatchTransactionsResponse(emptyList())
      }

    val stellarTransaction =
      StellarTransaction.builder()
        .id("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .memo("OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ")
        .memoType("hash")
        .createdAt(transferReceivedAt)
        .envelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .payments(
          listOf(
            StellarPayment.builder()
              .id("755914248193")
              .paymentType(StellarPayment.Type.PAYMENT)
              .sourceAccount("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
              .destinationAccount("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
              .amount(Amount("10.0000000", asset.toString()))
              .build()
          )
        )
        .build()

    paymentOperationToEventListener.onReceived(payment)
    verify(exactly = 1) {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_user_transfer_start"
      )
    }

    val capturedRequest = patchTxnRequestSlot.captured.records[0]
    assertEquals(
      SepTransactionStatus.PENDING_ANCHOR.status.toString(),
      capturedRequest.transaction.status.toString()
    )
    assertEquals(stellarTransaction.id, capturedRequest.transaction.stellarTransactions[0].id)
    assertEquals(transferReceivedAt, capturedRequest.transaction.transferReceivedAt)
    assertEquals(transferReceivedAt, capturedRequest.transaction.updatedAt)
    assertEquals(listOf(stellarTransaction), capturedRequest.transaction.stellarTransactions)
    assertEquals(sep6Txn.id, capturedRequest.transaction.id)
  }

  private fun createAsset(assetType: String, assetCode: String, assetIssuer: String?): Asset {
    return if (assetType == "native") {
      AssetTypeNative()
    } else {
      create(assetType, assetCode, assetIssuer)
    }
  }
}
