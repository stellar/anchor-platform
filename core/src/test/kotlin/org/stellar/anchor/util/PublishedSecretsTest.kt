package org.stellar.anchor.util

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PublishedSecretsTest {
  // Already public in the repository history; kept here only to prove the digests in main.
  private val publishedSecrets =
    mapOf(
      "SEP10_JWT_SECRET" to "10bb04a51338a1df86c2e807f8fe36168cf9a480d70c233452ec7e198ab33b7c",
      "SEP24_INTERACTIVE_URL_JWT_SECRET" to
        "c5457e3a349df9002117543efa7e316dd89e666a5ce6f33a0deb13e90f3f1e9d",
      "SEP24_MORE_INFO_URL_JWT_SECRET" to
        "b106cce1e32ebe342ea1e38d363fe048c7dc9c1b773658f83e22b78125785d89",
      "SEP6_MORE_INFO_URL_JWT_SECRET" to
        "3a614cf5da456aaad61dc7532f6c422fc2b833c0c05102b47b1ac2e8f0bff2e8",
      "SEP45_JWT_SECRET" to "1e5ba4a1da18dc6462b21bb720a5aceddb34a421e732e0f6615ad30b4a4aa50f",
      "CALLBACK_API_AUTH_SECRET" to "myPlatformToAnchorSecret",
      "PLATFORM_API_AUTH_SECRET" to "myAnchorToPlatformSecret"
    )
  private val publishedSeeds =
    listOf(
      "SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X",
      "SBTBRGMPWNLXOZQQQXXZRJKCZPYTAZT5FC4VEAECF2HOBCONTSH4EXKS",
    )

  @Test
  fun `test the digest list equals the digests of the published literals`() {
    assertEquals(
      publishedSecrets.values.map { PublishedSecrets.sha256Hex(it) }.toSet(),
      PublishedSecrets.secretDigests(),
    )
    assertEquals(
      publishedSeeds.map { PublishedSecrets.sha256Hex(it) }.toSet(),
      PublishedSecrets.seedDigests(),
    )
    assertEquals(7, PublishedSecrets.secretDigests().size)
    assertEquals(2, PublishedSecrets.seedDigests().size)
    publishedSecrets.forEach { (name, value) ->
      assertTrue(PublishedSecrets.isPublishedSecret(value), name)
    }
    publishedSeeds.forEach { assertTrue(PublishedSecrets.isPublishedSigningSeed(it)) }
  }

  @Test
  fun `test a one character variation of a published value is not published`() {
    publishedSecrets.forEach { (name, value) ->
      assertFalse(PublishedSecrets.isPublishedSecret(value + "x"), "$name plus a character")
      assertFalse(PublishedSecrets.isPublishedSecret(" $value"), "$name leading space")
      assertFalse(PublishedSecrets.isPublishedSecret("$value\n"), "$name trailing newline")
      assertFalse(PublishedSecrets.isPublishedSecret(value.dropLast(1)), "$name minus a character")
      assertFalse(PublishedSecrets.isPublishedSecret(value.uppercase()), "$name uppercased")
    }
    publishedSeeds.forEach {
      assertFalse(PublishedSecrets.isPublishedSigningSeed(it + "A"))
      assertFalse(PublishedSecrets.isPublishedSigningSeed(it.dropLast(1)))
    }
  }

  @Test
  fun `test a seed is not a published secret and a secret is not a published seed`() {
    assertFalse(PublishedSecrets.isPublishedSecret(publishedSeeds[0]))
    assertFalse(PublishedSecrets.isPublishedSigningSeed(publishedSecrets.values.first()))
  }

  @Test
  fun `test null and empty values are not published`() {
    assertFalse(PublishedSecrets.isPublishedSecret(null))
    assertFalse(PublishedSecrets.isPublishedSecret(""))
    assertFalse(PublishedSecrets.isPublishedSigningSeed(null))
    assertFalse(PublishedSecrets.isPublishedSigningSeed(""))
  }
}
