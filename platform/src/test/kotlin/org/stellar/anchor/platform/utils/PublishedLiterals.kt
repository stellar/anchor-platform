package org.stellar.anchor.platform.utils

import org.stellar.anchor.platform.config.PropertySecretConfig

/**
 * The values this repository once published (Helm secret store, default test profile, examples).
 * They are already public; the tests need them to prove they are rejected, warned about, or absent
 * from the shipped files.
 */
object PublishedLiterals {
  /** Secret property name to the value the Helm secret store published for it. */
  val secretsByProperty: Map<String, String> =
    mapOf(
      PropertySecretConfig.SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET to
        "3a614cf5da456aaad61dc7532f6c422fc2b833c0c05102b47b1ac2e8f0bff2e8",
      PropertySecretConfig.SECRET_SEP_10_JWT_SECRET to
        "10bb04a51338a1df86c2e807f8fe36168cf9a480d70c233452ec7e198ab33b7c",
      PropertySecretConfig.SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET to
        "c5457e3a349df9002117543efa7e316dd89e666a5ce6f33a0deb13e90f3f1e9d",
      PropertySecretConfig.SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET to
        "b106cce1e32ebe342ea1e38d363fe048c7dc9c1b773658f83e22b78125785d89",
      PropertySecretConfig.SECRET_SEP_45_JWT_SECRET to
        "1e5ba4a1da18dc6462b21bb720a5aceddb34a421e732e0f6615ad30b4a4aa50f",
      PropertySecretConfig.SECRET_CALLBACK_API_AUTH_SECRET to "myPlatformToAnchorSecret",
      PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET to "myAnchorToPlatformSecret",
    )

  /** The signing seeds committed in the default test profile and in the k8s example. */
  val signingSeeds: List<String> =
    listOf(
      "SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X",
      "SBTBRGMPWNLXOZQQQXXZRJKCZPYTAZT5FC4VEAECF2HOBCONTSH4EXKS",
    )

  /** The SIGNING_KEY that the published default-profile seed derives to. */
  const val SIGNING_KEY = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
}
