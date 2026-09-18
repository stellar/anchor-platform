package org.stellar.anchor.platform.observer.stellar

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.stellar.sdk.SorobanServer

class SacToAssetMapperTest {
  @Test
  fun `getAssetFromSac propagates instead of silently reporting a non-SAC when the RPC call itself fails`() {
    val mapper = SacToAssetMapper(SorobanServer(""))

    assertThrows(RuntimeException::class.java) {
      mapper.getAssetFromSac("CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA")
    }
  }
}
