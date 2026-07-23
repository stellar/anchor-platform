package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.platform.HealthCheckStatus
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.sdk.KeyPair
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse

class StellarRpcPaymentObserverRecoveryE2ETest {

  @Test
  fun `observer resumes polling after a transient RPC outage instead of permanently dying`() {
    val config =
      StellarPaymentObserverConfig().apply {
        silenceCheckInterval = 1
        silenceTimeout = 2
        silenceTimeoutRetries = 5
        initialStreamBackoffTime = 1
        maxStreamBackoffTime = 2
        initialEventBackoffTime = 1
        maxEventBackoffTime = 2
      }

    val sorobanServer = mockk<SorobanServer>(relaxed = true)
    val stellarRpc = mockk<StellarRpc>(relaxed = true)
    every { stellarRpc.sorobanServer } returns sorobanServer
    every { stellarRpc.getSorobanServer() } returns sorobanServer
    every { sorobanServer.getLatestLedger() } returns mockk { every { sequence } returns 100 }

    val assetService = mockk<AssetService>(relaxed = true)
    every { assetService.stellarAssets } returns
      listOf(StellarAssetInfo().apply { distributionAccount = KeyPair.random().accountId })

    val cursorStore = mockk<StellarPaymentStreamerCursorStore>(relaxed = true)
    val accountsManager = mockk<PaymentObservingAccountsManager>(relaxed = true)

    val pollCount = AtomicInteger(0)
    val outageActive = AtomicBoolean(false)
    every { sorobanServer.getEvents(any()) } answers
      {
        if (outageActive.get()) {
          throw NetworkException(503, "simulated transient RPC outage")
        }
        pollCount.incrementAndGet()
        mockk<GetEventsResponse> {
          every { events } returns emptyList()
          every { latestLedger } returns 100L
          every { cursor } returns "CUR"
        }
      }

    val observer =
      StellarRpcPaymentObserver(
        stellarRpc,
        config,
        emptyList<PaymentListener>(),
        accountsManager,
        cursorStore,
        MockSacToAssetMapper(),
        assetService,
      )

    try {
      observer.start()
      Thread.sleep(2500)
      val baselinePolls = pollCount.get()
      assertTrue(baselinePolls > 0, "observer never polled successfully before the induced outage")

      outageActive.set(true)
      Thread.sleep(6000)

      outageActive.set(false)
      val pollsAtRecoveryStart = pollCount.get()
      Thread.sleep(3000)

      assertTrue(
        pollCount.get() > pollsAtRecoveryStart,
        "observer never resumed polling after the outage ended - permanently dead, exactly report 3857031",
      )
      assertTrue(
        observer.check().status == HealthCheckStatus.GREEN,
        "observer health did not return to GREEN after recovery",
      )
    } finally {
      observer.shutdown()
    }
  }
}
