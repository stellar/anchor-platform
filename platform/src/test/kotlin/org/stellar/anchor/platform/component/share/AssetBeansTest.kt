package org.stellar.anchor.platform.component.share

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.sdk.SorobanServer

class AssetBeansTest {
  private fun sorobanServerOf(mapper: SacToAssetMapper): SorobanServer {
    val field = SacToAssetMapper::class.java.getDeclaredField("sorobanServer")
    field.isAccessible = true
    return field.get(mapper) as SorobanServer
  }

  @Test
  fun `sacToAssetMapper reuses the ledger client's SorobanServer when the ledger client is RPC`() {
    val stellarRpc = StellarRpc("https://rpc.example.org")

    val mapper = AssetBeans().sacToAssetMapper(mockk(relaxed = true), stellarRpc)

    assertSame(stellarRpc.sorobanServer, sorobanServerOf(mapper))
  }

  @Test
  fun `sacToAssetMapper builds a plain SorobanServer when the ledger client is not RPC`() {
    val config = mockk<StellarNetworkConfig> { every { rpcUrl } returns "https://rpc.example.org" }

    val mapper = AssetBeans().sacToAssetMapper(config, mockk<Horizon>())

    assertNotNull(sorobanServerOf(mapper))
  }
}
