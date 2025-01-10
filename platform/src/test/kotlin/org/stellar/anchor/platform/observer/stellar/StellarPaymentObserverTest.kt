@file:Suppress("unused")

package org.stellar.anchor.platform.observer.stellar

import com.google.gson.reflect.TypeToken
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import java.util.*
import javax.net.ssl.SSLProtocolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.HealthCheckStatus.RED
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.sdk.Server
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.requests.SSEStream
import org.stellar.sdk.responses.GsonSingleton
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.operations.OperationResponse

class StellarPaymentObserverTest {
  companion object {
    const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
  }

  @MockK lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  @MockK lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager

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
          paymentStreamerCursorStore
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
        paymentStreamerCursorStore
      )

    // 2.1 If fetching from the network throws an error, we return `null`
    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } throws IOException("Some IO Problem happened!")

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
          paymentStreamerCursorStore
        )
      )
    every { observer.startSSEStream() } returns stream
    observer.start()
    observer.handleFailure(Optional.of(SSLProtocolException("")))
    assertEquals(ObserverStatus.STREAM_ERROR, observer.status)

    val checkResult = observer.check()
    assertEquals(RED, checkResult.status)
  }
}
