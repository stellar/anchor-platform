package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.sdk.Asset

class PaymentHelperTest {
  private val contractId = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"

  @Test
  fun `resolveSacAsset returns the asset of a genuine SAC`() {
    val mapper = mockk<SacToAssetMapper>()
    every { mapper.getAssetFromSac(contractId) } returns Asset.createNativeAsset().toXdr()

    assertEquals(
      Asset.createNativeAsset().toXdr(),
      PaymentHelper.resolveSacAsset(mapper, contractId)
    )
  }

  @Test
  fun `resolveSacAsset returns null instead of failing the notification when the lookup fails`() {
    val mapper = mockk<SacToAssetMapper>()
    every { mapper.getAssetFromSac(contractId) } throws IllegalArgumentException("no rpc url")

    assertNull(PaymentHelper.resolveSacAsset(mapper, contractId))
  }
}
