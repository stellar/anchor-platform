package org.stellar.anchor.platform.guard

import java.io.File
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.utils.PublishedLiterals

/**
 * The files this repository ships (jar resources, templates, examples) carry no working identity.
 */
class ShippedFilesTest {
  private val repoRoot = File("..")

  private fun shipped(path: String): String = File(repoRoot, path).readText()

  @Test
  fun `test the default profile defines no signing seed`() {
    val profile = shipped("service-runner/src/main/resources/profiles/default/config.env")

    assertFalse(
      profile.lines().any { it.trim().startsWith("secret.sep10.signing_seed") },
      "the default profile ships inside the jar and must not define a signing seed",
    )
    PublishedLiterals.signingSeeds.forEach {
      assertFalse(profile.contains(it), "a published signing seed is still in the default profile")
    }
  }
}
