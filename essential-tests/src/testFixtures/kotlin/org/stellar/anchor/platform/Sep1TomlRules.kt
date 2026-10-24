package org.stellar.anchor.platform

import com.moandjiezana.toml.Toml
import java.net.URI
import org.stellar.sdk.KeyPair

/**
 * The SEP-1 content rules of a `stellar.toml`, as the SEP states them, for the tests to run on the
 * TOML the stack serves and on the TOMLs the repository ships. AP serves an operator-supplied TOML
 * verbatim, so these rules live in the tests, not in production code.
 *
 * [violations] answers one stable code per broken rule, empty when the TOML follows every rule:
 * `NOT_TOML`, `SIZE`, `NETWORK_PASSPHRASE`, `SIGNING_KEY`, `CURRENCY[i]:<field>`,
 * `LINKED_TOML[i]:EXTRA_FIELDS`, `LINKED_TOML[i]:URL`, `NOT_HTTPS:<field>`,
 * `TRAILING_SLASH:<field>`, `ORG_URL_DOMAIN`, `ATTESTATION_DOMAIN:<field>`.
 */
object Sep1TomlRules {
  /** `http://` URLs and any `ORG_URL` are accepted when the TOML is served from one of these. */
  val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "host.docker.internal")

  private const val MAX_BYTES = 100_000
  private val PASSPHRASES =
    setOf("Test SDF Network ; September 2015", "Public Global Stellar Network ; September 2015")
  private val STATUSES = setOf("live", "dead", "test", "private")
  private val ASSET_TYPES =
    setOf("fiat", "crypto", "nft", "stock", "bond", "commodity", "realestate", "other")
  private val TOP_URL_FIELDS =
    listOf(
      "FEDERATION_SERVER",
      "AUTH_SERVER",
      "TRANSFER_SERVER",
      "TRANSFER_SERVER_SEP0024",
      "KYC_SERVER",
      "WEB_AUTH_ENDPOINT",
      "WEB_AUTH_FOR_CONTRACTS_ENDPOINT",
      "HORIZON_URL",
      "DIRECT_PAYMENT_SERVER",
      "ANCHOR_QUOTE_SERVER",
    )
  private val DOCUMENTATION_URL_FIELDS =
    listOf(
      "ORG_URL",
      "ORG_LOGO",
      "ORG_PHYSICAL_ADDRESS_ATTESTATION",
      "ORG_PHONE_NUMBER_ATTESTATION",
    )
  private val CURRENCY_URL_FIELDS = listOf("image", "attestation_of_reserve", "approval_server")
  private val ATTESTATION_FIELDS =
    listOf("ORG_PHYSICAL_ADDRESS_ATTESTATION", "ORG_PHONE_NUMBER_ATTESTATION")

  fun violations(tomlText: String, servedFromHost: String): List<String> {
    val toml =
      try {
        Toml().read(tomlText)
      } catch (e: Exception) {
        return listOf("NOT_TOML")
      }
    val local = servedFromHost in LOCAL_HOSTS
    val found = mutableListOf<String>()

    if (tomlText.toByteArray(Charsets.UTF_8).size >= MAX_BYTES) found += "SIZE"
    if (toml.getString("NETWORK_PASSPHRASE") !in PASSPHRASES) found += "NETWORK_PASSPHRASE"
    if (!isPublicKey(toml.getString("SIGNING_KEY"))) found += "SIGNING_KEY"

    val urls = linkedMapOf<String, String>()
    TOP_URL_FIELDS.forEach { f -> toml.getString(f)?.let { urls[f] = it } }
    val documentation = toml.getTable("DOCUMENTATION")
    DOCUMENTATION_URL_FIELDS.forEach { f -> documentation?.getString(f)?.let { urls[f] = it } }

    toml.getTables("CURRENCIES").orEmpty().forEachIndexed { i, currency ->
      val values = currency.toMap()
      if (values.containsKey("toml")) {
        if (values.keys.any { it != "toml" }) found += "LINKED_TOML[$i]:EXTRA_FIELDS"
        val link = values["toml"] as? String
        if (link == null || !link.startsWith("https://") || link.endsWith("/")) {
          found += "LINKED_TOML[$i]:URL"
        }
      } else {
        found += currencyViolations(i, values)
        CURRENCY_URL_FIELDS.forEach { f ->
          (values[f] as? String)?.let { urls["CURRENCIES[$i].$f"] = it }
        }
      }
    }

    urls.forEach { (field, url) ->
      if (!url.startsWith("https://") && !(local && url.startsWith("http://"))) {
        found += "NOT_HTTPS:$field"
      }
      if (url.endsWith("/")) found += "TRAILING_SLASH:$field"
    }

    if (!local) found += sameDomainViolations(urls, servedFromHost)
    return found
  }

  private fun currencyViolations(i: Int, values: Map<String, Any?>): List<String> {
    val found = mutableListOf<String>()
    val code = values["code"] as? String
    if (code == null || code.isEmpty() || code.length > 12) found += "CURRENCY[$i]:code"
    if (values["desc"] !is String) found += "CURRENCY[$i]:desc"
    if (values["status"] !in STATUSES) found += "CURRENCY[$i]:status"
    if (values["is_asset_anchored"] !is Boolean) found += "CURRENCY[$i]:is_asset_anchored"
    if (values["anchor_asset_type"] !in ASSET_TYPES) found += "CURRENCY[$i]:anchor_asset_type"
    if (values.containsKey("issuer") && !isPublicKey(values["issuer"] as? String)) {
      found += "CURRENCY[$i]:issuer"
    }
    return found
  }

  private fun sameDomainViolations(
    urls: Map<String, String>,
    servedFromHost: String
  ): List<String> {
    val orgUrl = urls["ORG_URL"] ?: return emptyList()
    val orgHost = hostOf(orgUrl)
    val found = mutableListOf<String>()
    if (orgHost != servedFromHost) found += "ORG_URL_DOMAIN"
    ATTESTATION_FIELDS.forEach { f ->
      val host = urls[f]?.let { hostOf(it) } ?: return@forEach
      if (host != orgHost && !host.endsWith(".$orgHost")) found += "ATTESTATION_DOMAIN:$f"
    }
    return found
  }

  private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

  private fun isPublicKey(value: String?): Boolean =
    value != null && runCatching { KeyPair.fromAccountId(value) }.isSuccess
}
