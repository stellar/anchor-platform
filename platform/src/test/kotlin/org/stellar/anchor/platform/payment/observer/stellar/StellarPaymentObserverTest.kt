package org.stellar.anchor.platform.payment.observer.stellar

import com.google.gson.reflect.TypeToken
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.sdk.Server
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.GsonSingleton
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.operations.OperationResponse

class StellarPaymentObserverTest {
  companion object {
    const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
  }

  @MockK private lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @AfterEach
  fun tearDown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun `test if StellarPaymentObserver will fetch the cursor from the DB, then fallback to the Network`() {
    // 1 - If there is a stored cursor, we'll use that.
    every { paymentStreamerCursorStore.load() } returns "123"
    var stellarObserver =
      StellarPaymentObserver(TEST_HORIZON_URI, null, null, paymentStreamerCursorStore)

    var gotCursor = stellarObserver.fetchStreamingCursor()
    assertEquals("123", gotCursor)
    verify(exactly = 1) { paymentStreamerCursorStore.load() }

    // 2 - If there is no stored constructor, we will fall back to fetching a result from the
    // network.
    every { paymentStreamerCursorStore.load() } returns null
    mockkConstructor(Server::class)
    stellarObserver =
      StellarPaymentObserver(TEST_HORIZON_URI, null, null, paymentStreamerCursorStore)

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
}
