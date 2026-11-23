package org.stellar.anchor.asset

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.AssetInfo
import org.stellar.anchor.api.asset.StellarAssetInfo

/**
 * The quick-run template is the reference configuration of the stock stack. When its SEP-6/SEP-31
 * and SEP-38 method sets are disjoint, a quote can never disagree with the declared funding method,
 * which hid the missing check of ANCHOR-1341.
 */
internal class QuickRunAssetsTemplateTest {
  private val templatePath = Path.of("..", "quick-run", "config", "assets.yaml.template")
  private val fiatIds = listOf("iso4217:USD", "iso4217:CAD")

  private fun loadTemplate(): DefaultAssetService {
    val yaml =
      Files.readString(templatePath)
        .replace(
          "\${DISTRIBUTION_ACCOUNT}",
          "GD4KHPYMI4HBD3HMC5DZS4GLCS3G2F7ME2TWPDD2WFUGPXNAF4IKWRXG"
        )
    return DefaultAssetService.fromYamlContent(yaml)
  }

  private fun DefaultAssetService.asset(id: String): AssetInfo = assets.first { it.id == id }

  private fun sellMethods(asset: AssetInfo) = asset.sep38.sellDeliveryMethods.map { it.name }

  private fun buyMethods(asset: AssetInfo) = asset.sep38.buyDeliveryMethods.map { it.name }

  @Test
  fun `test the quick-run template lists SEPA and SWIFT as sell and buy delivery methods`() {
    val das = loadTemplate()

    for (id in fiatIds) {
      assertEquals(listOf("SEPA", "SWIFT"), sellMethods(das.asset(id)), "$id sell_delivery_methods")
      assertEquals(listOf("SEPA", "SWIFT"), buyMethods(das.asset(id)), "$id buy_delivery_methods")
    }
  }

  @Test
  fun `test the quick-run template method sets overlap`() {
    val das = loadTemplate()
    val native = das.asset("stellar:native") as StellarAssetInfo

    for (id in fiatIds) {
      val fiat = das.asset(id)
      // SEP-31 receive methods against the SEP-38 buy delivery methods
      assertTrue(
        fiat.sep31.receive.methods.intersect(buyMethods(fiat).toSet()).isNotEmpty(),
        "$id: sep31.receive.methods and sep38.buy_delivery_methods share no name"
      )
      // SEP-6 deposit methods against the SEP-38 sell delivery methods
      assertTrue(
        native.sep6.deposit.methods.intersect(sellMethods(fiat).toSet()).isNotEmpty(),
        "$id: stellar:native sep6.deposit.methods and sep38.sell_delivery_methods share no name"
      )
    }
  }

  @Test
  fun `test the quick-run template loads as a valid asset configuration`() {
    val das = loadTemplate()

    assertEquals(
      setOf("stellar:native", "iso4217:USD", "iso4217:CAD"),
      das.assets.map { it.id }.toSet()
    )
  }
}
