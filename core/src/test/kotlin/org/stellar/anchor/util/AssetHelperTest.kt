package org.stellar.anchor.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AssetHelperTest {
  @ParameterizedTest
  @CsvSource(value = ["USD,", "JPY,", "BRL,"])
  fun `check valid iso4217 assets`(assetCode: String, assetIssuer: String?) {
    assertTrue(AssetHelper.isISO4217(assetCode, assetIssuer))
  }

  @ParameterizedTest
  @CsvSource(value = ["USD,non-empty", "BADISO,", ",", ",non-empty"])
  fun `check invalid iso4217 assets`(assetCode: String?, assetIssuer: String?) {
    assertFalse(AssetHelper.isISO4217(assetCode, assetIssuer))
  }

  @Test
  fun `check valid native assets`() {
    assertTrue(AssetHelper.isNativeAsset("native", ""))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "USDC,GDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G74SS",
        "BRLC,GDECIOEWJLWMVILZCJILY7FIWUY6VOXVRTAD5AJ57YKRHP2SWPEWYGDG",
      ]
  )
  fun `check valid issued assets`(assetCode: String?, assetIssuer: String?) {
    assertTrue(AssetHelper.isNonNativeAsset(assetCode, assetIssuer))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "USDC,MDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G6AAAAAAAAAAAPOBAM",
        "USDC,",
        "USDC,BAD_WALLET",
        "BADASSET,BAD_WALLET",
        "BADASSET,",
        ",BAD_WALLET",
        "native,MDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G6AAAAAAAAAAAPOBAM",
        "native,"
      ]
  )
  fun `test invalid stellar assets`(assetCode: String?, assetIssuer: String?) {
    assertFalse(AssetHelper.isNonNativeAsset(assetCode, assetIssuer))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "USDC:GDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G74SS,USDC,GDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G74SS",
        "stellar:USDC:GDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G74SS,USDC,GDJJES5JOST5VTBLDVVQRAW26LZ5IIJJFVN5IJOMICM73HLGGB3G74SS",
        "iso4217:USD,USD,",
        "native,native,"
      ]
  )
  fun `test getAssetCode()`(asset: String, assetCode: String, assetIssuer: String?) {
    assertEquals(AssetHelper.getAssetCode(asset), assetCode)
    assertEquals(AssetHelper.getAssetIssuer(asset), assetIssuer)
  }
}
