@file:Suppress("unused")

package org.stellar.anchor.platform.service

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.StellarId
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.*
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
  @MockK(relaxed = true) private lateinit var rpcConfig: RpcConfig

  private lateinit var paymentOperationToEventListener: PaymentOperationToEventListener
  private val gson = GsonUtils.getInstance()

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any())
    } returns null
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndFromAccountAndStatus(
        any(),
        any(),
        any(),
      )
    } returns null

    paymentOperationToEventListener =
      PaymentOperationToEventListener(
        sep31TransactionStore,
        sep24TransactionStore,
        sep6TransactionStore,
        platformApiClient,
        rpcConfig,
      )
  }

  @Test
  fun test_onReceiver_failValidation() {
    // Payment missing txHash shouldn't trigger an event nor reach the DB
    val p = ObservedPayment.builder().build()
    p.transactionHash = null
    p.transactionMemoType = "text"
    p.transactionMemo = "my_memo_1"
    p.type = ObservedPayment.Type.PAYMENT
    paymentOperationToEventListener.onReceived(p)
    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }

    // Payment missing txMemo shouldn't trigger an event nor reach the DB
    p.transactionHash = "1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30"
    p.transactionMemo = null
    paymentOperationToEventListener.onReceived(p)
    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }

    // Asset types different from "native", "credit_alphanum4" and "credit_alphanum12" shouldn't
    // trigger an event nor reach the DB
    p.transactionHash = "1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30"
    p.transactionMemo = "my_memo_1"
    p.assetType = "liquidity_pool_shares"
    paymentOperationToEventListener.onReceived(p)
    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }

    // Payment whose memo is not in the DB shouldn't trigger event
    p.transactionHash = "1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30"
    p.transactionMemo = "my_memo_2"
    p.assetType = "credit_alphanum4"
    p.amount = "1"
    p.assetName = "FOO:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    p.sourceAccount = "GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5"
    p.to = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    var slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()
    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any())
    } returns null
    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_2",
        "pending_sender",
      )
    }
    assertEquals("my_memo_2", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_sender", slotStatus.captured)

    // If findByStellarAccountIdAndMemoAndStatus throws an exception, we shouldn't trigger an event
    slotMemo = slot()
    p.transactionMemo = "my_memo_3"
    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } throws SepException("Something went wrong")
    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_sender",
      )
    }
    assertEquals("my_memo_3", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_sender", slotStatus.captured)

    // If asset code from the fetched tx is different, don't trigger event
    slotMemo = slot()
    p.transactionMemo = "my_memo_4"
    p.assetCode = "FOO"
    val sep31TxMock = JdbcSep31Transaction()
    sep31TxMock.amountInAsset = "BAR"
    sep31TxMock.amountIn = "1"
    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep31TxMock
    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_4",
        "pending_sender",
      )
    }
    assertEquals("my_memo_4", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_sender", slotStatus.captured)
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
    outAssetIssuer: String?,
  ) {
    val startedAtMock = Instant.now().minusSeconds(120)
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val inAsset = createAsset(inAssetType, inAssetCode, inAssetIssuer)
    val outAsset = createAsset(outAssetType, outAssetCode, outAssetIssuer)

    val p =
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
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccountId),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep31TxCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_sender",
      )
    }

    assertEquals(sep31TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
  }

  @Test
  fun `test SEP-31 onReceived gets less than the expected amount it sends the PENDING_RECEIVER status with a message`() {
    val startedAtMock = Instant.now().minusSeconds(120)
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val fooAsset = "stellar:FOO:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val barAsset = "stellar:BAR:GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"

    val p =
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
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep31TxCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_sender",
      )
    }

    assertEquals(sep31TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
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
    assetIssuer: String?,
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val p =
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
    sep24TxMock.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    val sep24TxnCopy = gson.fromJson(gson.toJson(sep24TxMock), JdbcSep24Transaction::class.java)
    every {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep24TxnCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_user_transfer_start",
      )
    }

    assertEquals(sep24TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "native,native,",
        "credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      ]
  )
  fun `test SEP-24 onReceived with sufficient payment and no memo patches the transaction`(
    assetType: String,
    assetCode: String,
    assetIssuer: String?,
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val p =
      ObservedPayment.builder()
        .transactionHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .assetType(assetType)
        .assetCode(assetCode)
        .assetName(asset.toString())
        .assetIssuer(assetIssuer)
        .amount("10.0000000")
        .sourceAccount("GCJKWN7ELKOXLDHJTOU4TZOEJQL7TYVVTQFR676MPHHUIUDAHUA7QGJ4")
        .from("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
        .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .type(ObservedPayment.Type.SAC_TRANSFER)
        .createdAt(transferReceivedAtStr)
        .transactionEnvelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .id("755914248193")
        .build()

    val sep24TxMock = JdbcSep24Transaction()
    sep24TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep24TxMock.requestAssetCode = assetCode
    sep24TxMock.requestAssetIssuer = assetIssuer
    sep24TxMock.amountIn = "10.0000000"
    sep24TxMock.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    val sep24TxnCopy = gson.fromJson(gson.toJson(sep24TxMock), JdbcSep24Transaction::class.java)
    every {
      sep24TransactionStore.findOneByToAccountAndFromAccountAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO",
        any(),
      )
    } returns sep24TxnCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep24TransactionStore.findOneByToAccountAndFromAccountAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO",
        "pending_user_transfer_start",
      )
    }

    assertEquals(sep24TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
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
    assetIssuer: String?,
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val p =
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
    val sep6TxMock = JdbcSep6Transaction()
    sep6TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep6TxMock.requestAssetCode = assetCode
    sep6TxMock.requestAssetIssuer = assetIssuer
    sep6TxMock.amountExpected = "10.0000000"
    sep6TxMock.memo = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ"
    sep6TxMock.memoType = "hash"
    sep6TxMock.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    // TODO: this shouldn't be necessary
    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null

    val sep6TxnCopy = gson.fromJson(gson.toJson(sep6TxMock), JdbcSep6Transaction::class.java)
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep6TxnCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_user_transfer_start",
      )
    }

    assertEquals(sep6TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "native,native,",
        "credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      ]
  )
  fun `test SEP-6 onReceived with sufficient payment and no memo patches the transaction`(
    assetType: String,
    assetCode: String,
    assetIssuer: String?,
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val p =
      ObservedPayment.builder()
        .transactionHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .assetType(assetType)
        .assetCode(assetCode)
        .assetName(asset.toString())
        .assetIssuer(assetIssuer)
        .amount("10.0000000")
        .sourceAccount("GCJKWN7ELKOXLDHJTOU4TZOEJQL7TYVVTQFR676MPHHUIUDAHUA7QGJ4")
        .from("GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO")
        .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
        .type(ObservedPayment.Type.SAC_TRANSFER)
        .createdAt(transferReceivedAtStr)
        .transactionEnvelope(
          "AAAAAgAAAAAQfdFrLDgzSIIugR73qs8U0ZiKbwBUclTTPh5thlbgnAAAB9AAAACwAAAABAAAAAEAAAAAAAAAAAAAAABiMbeEAAAAAAAAABQAAAAAAAAAAAAAAADcXPrnCDi+IDcGSvu/HjP779qjBv6K9Sie8i3WDySaIgAAAAA8M2CAAAAAAAAAAAAAAAAAJXdMB+xylKwEPk1tOLU82vnDM0u15RsK6/HCKsY1O3MAAAAAPDNggAAAAAAAAAAAAAAAALn+JaJ9iXEcrPeRFqEMGo6WWFeOwW15H/vvCOuMqCsSAAAAADwzYIAAAAAAAAAAAAAAAADbWpHlX0LQjIjY0x8jWkclnQDK8jFmqhzCmB+1EusXwAAAAAA8M2CAAAAAAAAAAAAAAAAAmy3UTqTnhNzIg8TjCYiRh9l07ls0Hi5FTqelhfZ4KqAAAAAAPDNggAAAAAAAAAAAAAAAAIwiZIIbYJn7MbHrrM+Pg85c6Lcn0ZGLb8NIiXLEIPTnAAAAADwzYIAAAAAAAAAAAAAAAAAYEjPKA/6lDpr/w1Cfif2hK4GHeNODhw0kk4kgLrmPrQAAAAA8M2CAAAAAAAAAAAAAAAAASMrE32C3vL39cj84pIg2mt6OkeWBz5OSZn0eypcjS4IAAAAAPDNggAAAAAAAAAAAAAAAAIuxsI+2mSeh3RkrkcpQ8bMqE7nXUmdvgwyJS/dBThIPAAAAADwzYIAAAAAAAAAAAAAAAACuZxdjR/GXaymdc9y5WFzz2A8Yk5hhgzBZsQ9R0/BmZwAAAAA8M2CAAAAAAAAAAAAAAAAAAtWBvyq0ToNovhQHSLeQYu7UzuqbVrm0i3d1TjRm7WEAAAAAPDNggAAAAAAAAAAAAAAAANtrzNON0u1IEGKmVsm80/Av+BKip0ioeS/4E+Ejs9YPAAAAADwzYIAAAAAAAAAAAAAAAAD+ejNcgNcKjR/ihUx1ikhdz5zmhzvRET3LGd7oOiBlTwAAAAA8M2CAAAAAAAAAAAAAAAAASXG3P6KJjS6e0dzirbso8vRvZKo6zETUsEv7OSP8XekAAAAAPDNggAAAAAAAAAAAAAAAAC5orVpxxvGEB8ISTho2YdOPZJrd7UBj1Bt8TOjLOiEKAAAAADwzYIAAAAAAAAAAAAAAAAAOQR7AqdGyIIMuFLw9JQWtHqsUJD94kHum7SJS9PXkOwAAAAA8M2CAAAAAAAAAAAAAAAAAIosijRx7xSP/+GA6eAjGeV9wJtKDySP+OJr90euE1yQAAAAAPDNggAAAAAAAAAAAAAAAAKlHXWQvwNPeT4Pp1oJDiOpcKwS3d9sho+ha+6pyFwFqAAAAADwzYIAAAAAAAAAAAAAAAABjCjnoL8+FEP0LByZA9PfMLwU1uAX4Cb13rVs83e1UZAAAAAA8M2CAAAAAAAAAAAAAAAAAokhNCZNGq9uAkfKTNoNGr5XmmMoY5poQEmp8OVbit7IAAAAAPDNggAAAAAAAAAABhlbgnAAAAEBa9csgF5/0wxrYM6oVsbM4Yd+/3uVIplS6iLmPOS4xf8oLQLtjKKKIIKmg9Gc/yYm3icZyU7icy9hGjcujenMN"
        )
        .id("755914248193")
        .build()

    val sep6TxMock = JdbcSep6Transaction()
    sep6TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep6TxMock.requestAssetCode = assetCode
    sep6TxMock.requestAssetIssuer = assetIssuer
    sep6TxMock.amountExpected = "10.0000000"
    sep6TxMock.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null

    val sep6TxnCopy = gson.fromJson(gson.toJson(sep6TxMock), JdbcSep6Transaction::class.java)
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndFromAccountAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO",
        any(),
      )
    } returns sep6TxnCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndFromAccountAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "GAJKV32ZXP5QLYHPCMLTV5QCMNJR3W6ZKFP6HMDN67EM2ULDHHDGEZYO",
        "pending_user_transfer_start",
      )
    }

    assertEquals(sep6TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "native,native,",
        "credit_alphanum4,USD,GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      ]
  )
  fun `test SEP-6 onReceived with payment but no expected amount patches the transaction`(
    assetType: String,
    assetCode: String,
    assetIssuer: String?,
  ) {
    val transferReceivedAt = Instant.now()
    val transferReceivedAtStr = DateTimeFormatter.ISO_INSTANT.format(transferReceivedAt)
    val asset = createAsset(assetType, assetCode, assetIssuer)

    val p =
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
    val sep6TxMock = JdbcSep6Transaction()
    sep6TxMock.id = "ceaa7677-a5a7-434e-b02a-8e0801b3e7bd"
    sep6TxMock.requestAssetCode = assetCode
    sep6TxMock.requestAssetIssuer = assetIssuer
    sep6TxMock.memo = "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ"
    sep6TxMock.memoType = "hash"
    sep6TxMock.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    val sep6TxnCopy = gson.fromJson(gson.toJson(sep6TxMock), JdbcSep6Transaction::class.java)
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep6TxnCopy

    val txnIdCapture = slot<String>()
    val stellarTxnIdCapture = slot<String>()
    val amountCapture = slot<String>()
    val messageCapture = slot<String>()

    every { rpcConfig.customMessages.incomingPaymentReceived } returns "payment received"
    every {
      platformApiClient.notifyOnchainFundsReceived(
        capture(txnIdCapture),
        capture(stellarTxnIdCapture),
        capture(amountCapture),
        capture(messageCapture),
      )
    } just Runs

    paymentOperationToEventListener.onReceived(p)
    verify(exactly = 1) {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "OWI3OGYwZmEtOTNmOS00MTk4LThkOTMtZTc2ZmQwODQ=",
        "pending_user_transfer_start",
      )
    }

    assertEquals(sep6TxMock.id, txnIdCapture.captured)
    assertEquals(p.transactionHash, stellarTxnIdCapture.captured)
    assertEquals(p.amount, amountCapture.captured)
    assertEquals("payment received", messageCapture.captured)
  }

  private fun createAsset(assetType: String, assetCode: String, assetIssuer: String?): Asset {
    return if (assetType == "native") {
      AssetTypeNative()
    } else {
      create(assetType, assetCode, assetIssuer)
    }
  }
}
