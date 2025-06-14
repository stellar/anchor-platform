@file:Suppress("unused")

package org.stellar.anchor.platform

import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.ServerErrorException
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.platform.component.observer.PaymentObserverBeans
import org.stellar.anchor.platform.config.PaymentObserverConfig
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.*

class PaymentObservingAccountsBeansTest {
  @MockK private lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore

  @MockK private lateinit var paymentObservingAccountStore: PaymentObservingAccountStore

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
        paymentObserverBeans.stellarPaymentObserver(null, null, null, null, null, null, mockk())
      }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Asset service cannot be empty.", ex.message)

    // assetService.listAllAssets() is null
    val mockEmptyAssetService = mockk<AssetService>()
    every { mockEmptyAssetService.getAssets() } returns null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        mockEmptyAssetService,
        null,
        null,
        null,
        null,
        null,
        mockk<SacToAssetMapper>()
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
        mockStellarLessAssetService,
        null,
        null,
        null,
        null,
        null,
        mockk()
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Asset service should contain at least one Stellar asset.", ex.message)

    // paymentListeners is null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        assetService,
        null,
        null,
        null,
        null,
        null,
        mockk()
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("The stellar payment observer service needs at least one listener.", ex.message)

    // paymentListeners is empty
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        assetService,
        listOf(),
        null,
        null,
        null,
        null,
        mockk()
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("The stellar payment observer service needs at least one listener.", ex.message)

    // paymentStreamerCursorStore is null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        assetService,
        mockPaymentListeners,
        null,
        null,
        null,
        null,
        mockk()
      )
    }
    assertInstanceOf(ServerErrorException::class.java, ex)
    assertEquals("Payment streamer cursor store cannot be empty.", ex.message)

    // appConfig is null
    every { paymentStreamerCursorStore.load() } returns null
    ex = assertThrows {
      paymentObserverBeans.stellarPaymentObserver(
        assetService,
        mockPaymentListeners,
        paymentStreamerCursorStore,
        null,
        null,
        null,
        mockk()
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

    val mockAppConfig = mockk<AppConfig>()
    val mockPaymentObserverConfig = mockk<PaymentObserverConfig>()

    every { mockAppConfig.horizonUrl } returns "https://horizon-testnet.stellar.org"
    every { mockAppConfig.rpcUrl } returns null
    every { mockPaymentObserverConfig.stellar } returns
      StellarPaymentObserverConfig(1, 5, 1, 1, 2, 1, 2)

    assertDoesNotThrow {
      val paymentObserver =
        paymentObserverBeans.stellarPaymentObserver(
          assetService,
          mockPaymentListeners,
          paymentStreamerCursorStore,
          paymentObservingAccountsManager,
          mockAppConfig,
          mockPaymentObserverConfig,
          mockk()
        )
      assertNotNull(paymentObserver)
      assertTrue(paymentObserver is HorizonPaymentObserver)
    }

    every { mockAppConfig.rpcUrl } returns "https://soroban-testnet.stellar.org"

    assertDoesNotThrow {
      val paymentObserver =
        paymentObserverBeans.stellarPaymentObserver(
          assetService,
          mockPaymentListeners,
          paymentStreamerCursorStore,
          paymentObservingAccountsManager,
          mockAppConfig,
          mockPaymentObserverConfig,
          mockk()
        )
      assertNotNull(paymentObserver)
      assertTrue(paymentObserver is StellarRpcPaymentObserver)
    }

    every { mockAppConfig.horizonUrl } returns null
    every { mockAppConfig.rpcUrl } returns null

    assertThrows<IllegalArgumentException> {
      paymentObserverBeans.stellarPaymentObserver(
        assetService,
        mockPaymentListeners,
        paymentStreamerCursorStore,
        paymentObservingAccountsManager,
        mockAppConfig,
        mockPaymentObserverConfig,
        mockk()
      )
    }
  }
}
