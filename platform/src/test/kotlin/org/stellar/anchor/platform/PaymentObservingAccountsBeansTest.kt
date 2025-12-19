@file:Suppress("unused")

package org.stellar.anchor.platform

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.ServerErrorException
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.component.observer.PaymentObserverBeans
import org.stellar.anchor.platform.config.PaymentObserverConfig
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.*

class PaymentObservingAccountsBeansTest {
  @MockK private lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  @MockK private lateinit var paymentObservingAccountStore: PaymentObservingAccountStore

  val stellarRpc = StellarRpc("https://soroban-testnet.stellar.org")
  val horizon = Horizon("https://horizon-testnet.stellar.org")

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @Test
  fun test_stellarPaymentObserverService_failure() {
    val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")
    val paymentObserverBeans = PaymentObserverBeans()
    val mockPaymentListener = mockk<PaymentListener>()
    val mockPaymentListeners = listOf(mockPaymentListener)

    // assetService is null
    var ex =
      assertThrows<ServerErrorException> {
        paymentObserverBeans.stellarPaymentObserver(
          stellarRpc,
          null,
          null,
          null,
          null,
          null,
          null,
          mockk(),
        )
      }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Asset service cannot be empty.", ex.message)

    // assetService.listAllAssets() is null
    val mockEmptyAssetService = mockk<AssetService>()
    every { mockEmptyAssetService.getAssets() } returns null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        stellarRpc,
        mockEmptyAssetService,
        null,
        null,
        null,
        null,
        null,
        mockk<SacToAssetMapper>(),
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Asset service cannot be empty.", ex.message)

    // assetService.listAllAssets() doesn't contain stellar assets
    val mockStellarLessAssetService = mockk<AssetService>()
    every { mockStellarLessAssetService.getAssets() } returns listOf()
    every { mockStellarLessAssetService.getStellarAssets() } returns listOf()
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        horizon,
        mockStellarLessAssetService,
        null,
        null,
        null,
        null,
        null,
        mockk(),
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Asset service should contain at least one Stellar asset.", ex.message)

    // paymentListeners is null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        stellarRpc,
        assetService,
        null,
        null,
        null,
        null,
        null,
        mockk(),
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("The stellar payment observer service needs at least one listener.", ex.message)

    // paymentListeners is empty
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        stellarRpc,
        assetService,
        listOf(),
        null,
        null,
        null,
        null,
        mockk(),
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("The stellar payment observer service needs at least one listener.", ex.message)

    // paymentStreamerCursorStore is null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        stellarRpc,
        assetService,
        mockPaymentListeners,
        null,
        null,
        null,
        null,
        mockk(),
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Payment streamer cursor store cannot be empty.", ex.message)

    // appConfig is null
    every { paymentStreamerCursorStore.loadHorizonCursor() } returns null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        stellarRpc,
        assetService,
        mockPaymentListeners,
        paymentStreamerCursorStore,
        null,
        null,
        null,
        mockk(),
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("AppConfig cannot be empty.", ex.message)
  }

  @Test
  fun test_givenGoodManager_whenConstruct_thenOk() {
    // success!
    val paymentObserverBeans = PaymentObserverBeans()
    val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")
    val mockPaymentListener = mockk<PaymentListener>()
    val mockPaymentListeners = listOf(mockPaymentListener)

    val paymentObservingAccountsManager =
      PaymentObservingAccountsManager(paymentObservingAccountStore)

    val mockStellarNetworkConfig = mockk<StellarNetworkConfig>()
    val mockPaymentObserverConfig = mockk<PaymentObserverConfig>()

    every { mockStellarNetworkConfig.horizonUrl } returns "https://horizon-testnet.stellar.org"
    every { mockStellarNetworkConfig.rpcUrl } returns null
    every { mockPaymentObserverConfig.stellar } returns
      StellarPaymentObserverConfig(1, 5, 1, 1, 2, 1, 2)

    assertDoesNotThrow {
      val paymentObserver =
        paymentObserverBeans.stellarPaymentObserver(
          horizon,
          assetService,
          mockPaymentListeners,
          paymentStreamerCursorStore,
          paymentObservingAccountsManager,
          mockStellarNetworkConfig,
          mockPaymentObserverConfig,
          mockk(),
        )
      assertNotNull(paymentObserver)
      assertTrue(paymentObserver is HorizonPaymentObserver)
    }

    every { mockStellarNetworkConfig.rpcUrl } returns "https://soroban-testnet.stellar.org"

    assertDoesNotThrow {
      val paymentObserver =
        paymentObserverBeans.stellarPaymentObserver(
          stellarRpc,
          assetService,
          mockPaymentListeners,
          paymentStreamerCursorStore,
          paymentObservingAccountsManager,
          mockStellarNetworkConfig,
          mockPaymentObserverConfig,
          mockk(),
        )
      assertNotNull(paymentObserver)
      assertTrue(paymentObserver is StellarRpcPaymentObserver)
    }
  }
}
