package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.sdk.Address
import org.stellar.sdk.Asset
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.responses.sorobanrpc.GetLedgerEntriesResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.*

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

  @Test
  fun `getAssetFromSac does not cache an empty getLedgerEntries answer`() {
    val sorobanServer = mockk<SorobanServer>()
    every { sorobanServer.getLedgerEntries(any()) } returns entriesResponse(emptyList())
    val mapper = SacToAssetMapper(sorobanServer)

    assertNull(mapper.getAssetFromSac(SAC))
    assertNull(mapper.getAssetFromSac(SAC))

    verify(exactly = 2) { sorobanServer.getLedgerEntries(any()) }
    assertFalse(mapper.isKnownNonSac(SAC))
  }

  @Test
  fun `getAssetFromSac resolves a SAC after an earlier empty answer`() {
    val sorobanServer = mockk<SorobanServer>()
    every { sorobanServer.getLedgerEntries(any()) } returnsMany
      listOf(entriesResponse(emptyList()), entriesResponse(listOf(sacInstanceEntry())))
    val mapper = SacToAssetMapper(sorobanServer)

    assertNull(mapper.getAssetFromSac(SAC))
    assertEquals(Asset.create(USDC).toXdr(), mapper.getAssetFromSac(SAC))
    assertFalse(mapper.isKnownNonSac(SAC))
  }

  @Test
  fun `getAssetFromSac caches a contract whose executable is not a SAC`() {
    val sorobanServer = mockk<SorobanServer>()
    every { sorobanServer.getLedgerEntries(any()) } returns
      entriesResponse(listOf(wasmInstanceEntry()))
    val mapper = SacToAssetMapper(sorobanServer)

    assertNull(mapper.getAssetFromSac(SAC))
    assertNull(mapper.getAssetFromSac(SAC))
    assertNull(mapper.getAssetFromSac(SAC))

    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }
    assertTrue(mapper.isKnownNonSac(SAC))
  }

  @Test
  fun `getAssetFromSac caches a resolved SAC`() {
    val sorobanServer = mockk<SorobanServer>()
    every { sorobanServer.getLedgerEntries(any()) } returns
      entriesResponse(listOf(sacInstanceEntry()))
    val mapper = SacToAssetMapper(sorobanServer)

    assertEquals(Asset.create(USDC).toXdr(), mapper.getAssetFromSac(SAC))
    assertEquals(Asset.create(USDC).toXdr(), mapper.getAssetFromSac(SAC))

    verify(exactly = 1) { sorobanServer.getLedgerEntries(any()) }
    assertFalse(mapper.isKnownNonSac(SAC))
  }

  private fun entriesResponse(
    entries: List<GetLedgerEntriesResponse.LedgerEntryResult>
  ): GetLedgerEntriesResponse {
    val response = mockk<GetLedgerEntriesResponse>()
    every { response.entries } returns entries
    return response
  }

  private fun wasmInstanceEntry(): GetLedgerEntriesResponse.LedgerEntryResult =
    instanceEntry(
      ContractExecutable.builder()
        .discriminant(ContractExecutableType.CONTRACT_EXECUTABLE_WASM)
        .wasm_hash(Hash(ByteArray(32)))
        .build(),
      null
    )

  private fun sacInstanceEntry(): GetLedgerEntriesResponse.LedgerEntryResult {
    val metadata = Scv.toMap(linkedMapOf(Scv.toSymbol("name") to Scv.toString(USDC)))
    val storage =
      arrayOf(SCMapEntry.builder().key(Scv.toSymbol("METADATA")).`val`(metadata).build())
    return instanceEntry(
      ContractExecutable.builder()
        .discriminant(ContractExecutableType.CONTRACT_EXECUTABLE_STELLAR_ASSET)
        .build(),
      SCMap(storage)
    )
  }

  private fun instanceEntry(
    executable: ContractExecutable,
    storage: SCMap?
  ): GetLedgerEntriesResponse.LedgerEntryResult {
    val data =
      LedgerEntry.LedgerEntryData.builder()
        .discriminant(LedgerEntryType.CONTRACT_DATA)
        .contractData(
          ContractDataEntry.builder()
            .ext(ExtensionPoint.builder().discriminant(0).build())
            .contract(Address(SAC).toSCAddress())
            .key(Scv.toLedgerKeyContractInstance())
            .durability(ContractDataDurability.PERSISTENT)
            .`val`(
              SCVal.builder()
                .discriminant(SCValType.SCV_CONTRACT_INSTANCE)
                .instance(
                  SCContractInstance.builder().executable(executable).storage(storage).build()
                )
                .build()
            )
            .build()
        )
        .build()
    val result = mockk<GetLedgerEntriesResponse.LedgerEntryResult>()
    every { result.xdr } returns data.toXdrBase64()
    return result
  }
}

private const val SAC = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
private const val USDC = "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
