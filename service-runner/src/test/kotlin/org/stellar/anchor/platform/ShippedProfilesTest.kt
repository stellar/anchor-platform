package org.stellar.anchor.platform

import java.io.File
import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.util.NetUtil

class ShippedProfilesTest {
  private val profilesDir: File =
    generateSequence(File(".").absoluteFile.canonicalFile) { it.parentFile }
      .map { File(it, "service-runner/src/main/resources/profiles") }
      .first { it.isDirectory }

  private val ledgerUrlKeys = listOf("stellar_network.horizon_url", "stellar_network.rpc_url")

  private fun profileConfigs(): List<File> =
    profilesDir
      .listFiles { f -> f.isDirectory }!!
      .map { File(it, "config.env") }
      .filter { it.isFile }
      .sortedBy { it.path }

  @Test
  fun `every shipped profile ledger URL is https or loopback http`() {
    val violations = mutableListOf<String>()
    var urlsInspected = 0
    profileConfigs().forEach { file ->
      val props = Properties().apply { file.inputStream().use { load(it) } }
      ledgerUrlKeys.forEach { key ->
        val value = props.getProperty(key) ?: return@forEach
        urlsInspected++
        if (!NetUtil.isHttpsOrLoopbackHttp(value)) {
          violations += "${file.parentFile.name}/config.env: $key=$value"
        }
      }
    }
    assertTrue(urlsInspected > 0, "no stellar_network URL found in any shipped profile")
    assertEquals(emptyList<String>(), violations)
  }

  @Test
  fun `the profile scan inspects every config env file under the profiles directory`() {
    // A recursive walk, independent of the one-level scan the URL test uses: a config.env placed
    // deeper than profiles/<name>/ would be missed by that scan and fail here.
    val allConfigFiles = profilesDir.walkTopDown().filter { it.isFile && it.name == "config.env" }
    assertTrue(allConfigFiles.count() > 0, "no config.env found under $profilesDir")
    assertEquals(
      allConfigFiles.map { it.path }.sorted().toList(),
      profileConfigs().map { it.path }.sorted()
    )
  }
}
