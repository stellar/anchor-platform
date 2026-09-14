package org.stellar.anchor.platform.observer.stellar

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.stellar.sdk.SorobanServer

class SacToAssetMapperTest {
  @Test
  fun `getAssetFromSac fails closed instead of throwing when no RPC endpoint is configured`() {
    val mapper = SacToAssetMapper(SorobanServer(""))

    var asset: org.stellar.sdk.xdr.Asset? = null
    assertDoesNotThrow {
      asset = mapper.getAssetFromSac("CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA")
    }

    assertNull(asset)
  }
}
