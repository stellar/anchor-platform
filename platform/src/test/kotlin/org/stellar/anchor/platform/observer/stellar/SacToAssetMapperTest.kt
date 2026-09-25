package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse

class SacToAssetMapperTest {
  @Test
  fun `getAssetFromSac propagates instead of silently reporting a non-SAC when the RPC call itself fails`() {
    val mapper = SacToAssetMapper(SorobanServer(""))

    assertThrows(RuntimeException::class.java) {
      mapper.getAssetFromSac("CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA")
    }
  }

  @Test
  fun `getAssetFromSac returns null when the contract instance is not found`() {
    val sorobanServer = mockk<SorobanServer>()
    val response = mockk<GetLedgerEntriesResponse>()
    every { response.entries } returns emptyList()
    every { sorobanServer.getLedgerEntries(any()) } returns response

    assertNull(
      SacToAssetMapper(sorobanServer)
        .getAssetFromSac("CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA")
    )
  }
}
