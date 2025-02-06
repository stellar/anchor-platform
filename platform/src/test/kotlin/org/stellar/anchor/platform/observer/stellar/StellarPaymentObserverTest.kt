@file:Suppress("unused")

package org.stellar.anchor.platform.observer.stellar

import com.google.gson.reflect.TypeToken
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.util.*
import javax.net.ssl.SSLProtocolException
import org.joda.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.HealthCheckStatus.RED
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.ObservedPayment
import org.stellar.anchor.platform.observer.ObservedPayment.Type
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.sdk.Memo
import org.stellar.sdk.Server
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.requests.SSEStream
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.responses.gson.GsonSingleton
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse

class StellarPaymentObserverTest {
  companion object {
    const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
  }

  @MockK lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  @MockK lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  @MockK lateinit var paymentListener: PaymentListener

  val stellarPaymentObserverConfig = StellarPaymentObserverConfig(1, 5, 1, 1, 2, 1, 2)

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @Test
  fun `test if StellarPaymentObserver will fetch the cursor from the DB, then fallback to the Network`() {
    // 1 - If there is a stored cursor, we'll use that.
    every { paymentStreamerCursorStore.load() } returns "123"
    var stellarObserver =
      spyk(
        StellarPaymentObserver(
          TEST_HORIZON_URI,
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )

    every { stellarObserver.fetchLatestCursorFromNetwork() } returns "1000"
    var gotCursor = stellarObserver.fetchStreamingCursor()
    assertEquals("800", gotCursor)
    verify(exactly = 1) { paymentStreamerCursorStore.load() }

    // 2 - If there is no stored constructor, we will fall back to fetching a result from the
    // network.
    every { paymentStreamerCursorStore.load() } returns null
    mockkConstructor(Server::class)
    stellarObserver =
      StellarPaymentObserver(
        TEST_HORIZON_URI,
        stellarPaymentObserverConfig,
        null,
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )

    // 2.1 If fetching from the network throws an error, we return `null`
    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } throws NetworkException(null, "Some IO Problem happened!")

    gotCursor = stellarObserver.fetchStreamingCursor()
    verify(exactly = 2) { paymentStreamerCursorStore.load() }
    verify(exactly = 1) {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    }
    assertNull(gotCursor)

    // 2.2 If fetching from the network does not return any result, we return `null`
    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } returns null

    gotCursor = stellarObserver.fetchStreamingCursor()
    verify(exactly = 3) { paymentStreamerCursorStore.load() }
    verify(exactly = 2) {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    }
    assertNull(gotCursor)

    // 2.3 If fetching from the network returns a value, use that.
    val opPageJson =
      """{
      "_embedded": {
        "records": [
          {
            "paging_token": "4322708489777153",
            "type_i": 0
          }
        ]
      }
    }"""
    val operationPageType = object : TypeToken<Page<OperationResponse?>?>() {}.type
    val operationPage: Page<OperationResponse> =
      GsonSingleton.getInstance().fromJson(opPageJson, operationPageType)

    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } returns operationPage

    gotCursor = stellarObserver.fetchStreamingCursor()
    verify(exactly = 4) { paymentStreamerCursorStore.load() }
    verify(exactly = 3) {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    }
    assertEquals("4322708489777153", gotCursor)
  }

  @Test
  fun `test if SSEStream exception will leave the observer in STREAM_ERROR state`() {
    val stream: SSEStream<OperationResponse> = mockk(relaxed = true)
    val observer =
      spyk(
        StellarPaymentObserver(
          TEST_HORIZON_URI,
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { observer.startSSEStream() } returns stream
    observer.start()
    observer.handleFailure(Optional.of(SSLProtocolException("")))
    assertEquals(ObserverStatus.STREAM_ERROR, observer.status)

    val checkResult = observer.check()
    assertEquals(RED, checkResult.status)
  }

  @Test
  fun `test if PaymentOperation is parsed and handled by listener`() {
    val observer =
      spyk(
        StellarPaymentObserver(
          TEST_HORIZON_URI,
          stellarPaymentObserverConfig,
          listOf(paymentListener),
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    val paymentOp = mockk<PaymentOperationResponse>(relaxed = true)
    val transaction = mockk<TransactionResponse>(relaxed = true)
    every { transaction.memo } returns Memo.none()
    every { transaction.envelopeXdr } returns "AAAAA"

    every { paymentOp.id } returns 123L
    every { paymentOp.sourceAccount } returns "GSOURCE"
    every { paymentOp.createdAt } returns Instant.ofEpochSecond(1).toString()
    every { paymentOp.transactionHash } returns "123"
    every { paymentOp.transactionSuccessful } returns true
    every { paymentOp.transaction } returns transaction
    every { paymentOp.amount } returns "1"
    every { paymentOp.assetType } returns "native"
    every { paymentOp.assetCode } returns null
    every { paymentOp.assetIssuer } returns null
    every { paymentOp.from } returns "GFROM"
    every { paymentOp.to } returns "GTO"
    observer.handleEvent(paymentOp)

    verify { paymentObservingAccountsManager.lookupAndUpdate("GTO") }
    verify { paymentObservingAccountsManager.lookupAndUpdate("GFROM") }

    val observedPayment =
      ObservedPayment.builder()
        .id(paymentOp.id.toString())
        .type(Type.PAYMENT)
        .from(paymentOp.from)
        .to(paymentOp.to)
        .amount(paymentOp.amount)
        .assetType(paymentOp.assetType)
        .assetCode(paymentOp.assetCode)
        .assetIssuer(paymentOp.assetIssuer)
        .assetName(paymentOp.asset.toString())
        .sourceAccount(paymentOp.sourceAccount)
        .createdAt(paymentOp.createdAt)
        .transactionHash(paymentOp.transactionHash)
        .transactionMemo("")
        .transactionMemoType("none")
        .transactionEnvelope(paymentOp.transaction.envelopeXdr)
        .build()

    verify(exactly = 1) { paymentListener.onReceived(observedPayment) }
    verify(exactly = 1) { paymentListener.onSent(observedPayment) }
  }

  @Test
  fun `test if PathPaymentOperation is parsed and handled by listener`() {
    val observer =
      spyk(
        StellarPaymentObserver(
          TEST_HORIZON_URI,
          stellarPaymentObserverConfig,
          listOf(paymentListener),
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    val pathPaymentOp = mockk<PathPaymentBaseOperationResponse>(relaxed = true)
    val transaction = mockk<TransactionResponse>(relaxed = true)
    every { transaction.memo } returns Memo.none()
    every { transaction.envelopeXdr } returns "AAAAA"

    every { pathPaymentOp.id } returns 123L
    every { pathPaymentOp.sourceAccount } returns "GSOURCE"
    every { pathPaymentOp.createdAt } returns Instant.ofEpochSecond(1).toString()
    every { pathPaymentOp.transactionHash } returns "123"
    every { pathPaymentOp.transactionSuccessful } returns true
    every { pathPaymentOp.transaction } returns transaction
    every { pathPaymentOp.amount } returns "1"
    every { pathPaymentOp.sourceAmount } returns "1"
    every { pathPaymentOp.assetType } returns "native"
    every { pathPaymentOp.assetCode } returns null
    every { pathPaymentOp.assetIssuer } returns null
    every { pathPaymentOp.sourceAssetType } returns "native"
    every { pathPaymentOp.sourceAssetCode } returns null
    every { pathPaymentOp.sourceAssetIssuer } returns null
    every { pathPaymentOp.from } returns "GFROM"
    every { pathPaymentOp.to } returns "GTO"
    observer.handleEvent(pathPaymentOp)

    verify { paymentObservingAccountsManager.lookupAndUpdate("GTO") }
    verify { paymentObservingAccountsManager.lookupAndUpdate("GFROM") }

    val observedPayment =
      ObservedPayment.builder()
        .id(pathPaymentOp.id.toString())
        .type(Type.PATH_PAYMENT)
        .from(pathPaymentOp.from)
        .to(pathPaymentOp.to)
        .amount(pathPaymentOp.amount)
        .assetType(pathPaymentOp.assetType)
        .assetCode(pathPaymentOp.assetCode)
        .assetIssuer(pathPaymentOp.assetIssuer)
        .assetName(pathPaymentOp.asset.toString())
        .sourceAmount(pathPaymentOp.sourceAmount)
        .sourceAssetType(pathPaymentOp.sourceAssetType)
        .sourceAssetCode(pathPaymentOp.sourceAssetCode)
        .sourceAssetIssuer(pathPaymentOp.sourceAssetIssuer)
        .sourceAssetName(pathPaymentOp.sourceAsset.toString())
        .sourceAccount(pathPaymentOp.sourceAccount)
        .createdAt(pathPaymentOp.createdAt)
        .transactionHash(pathPaymentOp.transactionHash)
        .transactionMemo("")
        .transactionMemoType("none")
        .transactionEnvelope(pathPaymentOp.transaction.envelopeXdr)
        .build()

    verify(exactly = 1) { paymentListener.onReceived(observedPayment) }
    verify(exactly = 1) { paymentListener.onSent(observedPayment) }
  }

  @Test
  fun `test if InvokeHostFunctionOperation is parsed and handled by listener`() {
    val observer =
      spyk(
        StellarPaymentObserver(
          TEST_HORIZON_URI,
          stellarPaymentObserverConfig,
          listOf(paymentListener),
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    val invokeHostFunctionOp = mockk<InvokeHostFunctionOperationResponse>(relaxed = true)
    val transaction = mockk<TransactionResponse>(relaxed = true)
    every { transaction.memo } returns Memo.none()
    every { transaction.envelopeXdr } returns "AAAAA"

    val balanceChange =
      mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>(relaxed = true)
    every { balanceChange.from } returns "GFROM"
    every { balanceChange.to } returns "GTO"
    every { balanceChange.amount } returns "1"
    every { balanceChange.assetType } returns "native"
    every { balanceChange.assetCode } returns null
    every { balanceChange.assetIssuer } returns null

    every { invokeHostFunctionOp.id } returns 123L
    every { invokeHostFunctionOp.sourceAccount } returns "GSOURCE"
    every { invokeHostFunctionOp.createdAt } returns Instant.ofEpochSecond(1).toString()
    every { invokeHostFunctionOp.transactionHash } returns "123"
    every { invokeHostFunctionOp.transactionSuccessful } returns true
    every { invokeHostFunctionOp.transaction } returns transaction
    every { invokeHostFunctionOp.assetBalanceChanges } returns listOf(balanceChange)

    observer.handleEvent(invokeHostFunctionOp)

    verify { paymentObservingAccountsManager.lookupAndUpdate("GTO") }
    verify { paymentObservingAccountsManager.lookupAndUpdate("GFROM") }

    val observedPayment =
      ObservedPayment.builder()
        .id(invokeHostFunctionOp.id.toString())
        .type(Type.SAC_TRANSFER)
        .from(balanceChange.from)
        .to(balanceChange.to)
        .amount(balanceChange.amount)
        .assetType(balanceChange.assetType)
        .assetCode(balanceChange.assetCode)
        .assetIssuer(balanceChange.assetIssuer)
        .assetName(balanceChange.asset.toString())
        .sourceAccount(invokeHostFunctionOp.sourceAccount)
        .createdAt(invokeHostFunctionOp.createdAt)
        .transactionHash(invokeHostFunctionOp.transactionHash)
        .transactionEnvelope(invokeHostFunctionOp.transaction.envelopeXdr)
        .build()

    verify(exactly = 1) { paymentListener.onReceived(observedPayment) }
    verify(exactly = 1) { paymentListener.onSent(observedPayment) }
  }
}
