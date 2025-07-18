package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.platform.data.JdbcSep6Transaction

class Sep6DepositInfoSelfGeneratorTest {

  companion object {
    @JvmStatic
    fun assets(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(
          "testId1",
          "USDC",
          "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
          "GBJDTHT4562X2H37JMOE6IUTZZSDU6RYGYUNFYCHVFG3J4MYJIMU33HK",
          "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMHRlc3RJZDE=",
        ),
        Arguments.of("testId2", "USD", null, null, "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMHRlc3RJZDI="),
      )
    }
  }

  private val assetService: AssetService = DefaultAssetService.fromJsonResource("test_assets.json")

  private lateinit var generator: Sep6DepositInfoSelfGenerator

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    generator = Sep6DepositInfoSelfGenerator(assetService)
  }

  @ParameterizedTest
  @MethodSource("assets")
  fun test_sep6_selfGenerator_success(
    txnId: String,
    assetCode: String,
    assetIssuer: String?,
    distributionAccount: String?,
  ) {
    val txn = JdbcSep6Transaction()
    txn.id = txnId
    txn.requestAssetCode = assetCode
    txn.requestAssetIssuer = assetIssuer

    val result = generator.generate(txn)

    assertEquals(result.stellarAddress, distributionAccount)
    assertTrue(result.memo.toLongOrNull() != null)
    assertTrue(result.memoType == "id")
  }
}
