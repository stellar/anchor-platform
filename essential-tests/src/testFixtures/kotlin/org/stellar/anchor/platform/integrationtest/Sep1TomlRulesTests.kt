package org.stellar.anchor.platform.integrationtest

import com.moandjiezana.toml.Toml
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.platform.Sep1TomlRules

/**
 * The test-side SEP-1 TOML validator, proven with a TOML that follows every rule and one TOML per
 * rule that breaks only that rule, so a rule that stops checking fails here and not silently on the
 * real TOMLs.
 */
class Sep1TomlRulesTests {
  private val host = "anchor.example.com"
  private val signingKey = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
  private val issuer = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"

  private val good =
    """
    VERSION = "0.1.0"
    SIGNING_KEY = "$signingKey"
    NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"

    WEB_AUTH_ENDPOINT = "https://$host/auth"
    WEB_AUTH_FOR_CONTRACTS_ENDPOINT = "https://$host/sep45/auth"
    KYC_SERVER = "https://$host/sep12"
    TRANSFER_SERVER = "https://$host/sep6"
    TRANSFER_SERVER_SEP0024 = "https://$host/sep24"
    DIRECT_PAYMENT_SERVER = "https://$host/sep31"
    ANCHOR_QUOTE_SERVER = "https://$host/sep38"

    [[CURRENCIES]]
    code = "USDC"
    issuer = "$issuer"
    status = "test"
    is_asset_anchored = false
    desc = "Anchor Issued USDC Token"
    anchor_asset_type = "crypto"

    [[CURRENCIES]]
    code = "native"
    status = "test"
    is_asset_anchored = false
    anchor_asset_type = "crypto"
    desc = "XLM, the native token of the Stellar network."

    [DOCUMENTATION]
    ORG_URL = "https://$host"
    ORG_LOGO = "https://$host/logo.png"
    ORG_PHYSICAL_ADDRESS_ATTESTATION = "https://$host/address.pdf"
    """
      .trimIndent()

  private fun violations(toml: String, servedFrom: String = host) =
    Sep1TomlRules.violations(toml, servedFrom)

  private fun TomlCase(name: String, expected: String, toml: String) = Triple(name, expected, toml)

  // SEP1TF-10
  @Test
  fun `test a TOML that follows every rule has no violations`() {
    assertEquals(emptyList<String>(), violations(good))
  }

  // SEP1TF-10
  @Test
  fun `test each rule reports exactly its own violation`() {
    val cases =
      listOf(
        TomlCase("not a TOML", "NOT_TOML", "this = is = not toml"),
        TomlCase("too large", "SIZE", good + "\n" + "#".repeat(100_000)),
        TomlCase(
          "passphrase",
          "NETWORK_PASSPHRASE",
          good.replace("Test SDF Network ; September 2015", "foo"),
        ),
        TomlCase("signing key", "SIGNING_KEY", good.replace(signingKey, "GABC")),
        TomlCase(
          "code too long",
          "CURRENCY[0]:code",
          good.replace("\"USDC\"", "\"ABCDEFGHIJKLM\"")
        ),
        TomlCase(
          "desc missing",
          "CURRENCY[0]:desc",
          good.replace("desc = \"Anchor Issued USDC Token\"\n", ""),
        ),
        TomlCase(
          "status",
          "CURRENCY[0]:status",
          good.replaceFirst("status = \"test\"", "status = \"foo\"")
        ),
        TomlCase(
          "is_asset_anchored not boolean",
          "CURRENCY[0]:is_asset_anchored",
          good.replaceFirst("is_asset_anchored = false", "is_asset_anchored = \"yes\""),
        ),
        TomlCase(
          "anchor_asset_type",
          "CURRENCY[0]:anchor_asset_type",
          good.replaceFirst("anchor_asset_type = \"crypto\"", "anchor_asset_type = \"foo\""),
        ),
        TomlCase("issuer", "CURRENCY[0]:issuer", good.replace(issuer, "GABC")),
        TomlCase(
          "linked TOML with another field",
          "LINKED_TOML[2]:EXTRA_FIELDS",
          good + "\n[[CURRENCIES]]\ntoml = \"https://$host/USDC.toml\"\ncode = \"USDC\"\n",
        ),
        TomlCase(
          "linked TOML over http",
          "LINKED_TOML[2]:URL",
          good + "\n[[CURRENCIES]]\ntoml = \"http://$host/USDC.toml\"\n",
        ),
        TomlCase(
          "top-level URL over http",
          "NOT_HTTPS:KYC_SERVER",
          good.replace("https://$host/sep12", "http://$host/sep12"),
        ),
        TomlCase(
          "documentation URL over http",
          "NOT_HTTPS:ORG_LOGO",
          good.replace("https://$host/logo.png", "http://$host/logo.png"),
        ),
        TomlCase(
          "currency URL over http",
          "NOT_HTTPS:CURRENCIES[0].image",
          good.replaceFirst(
            "anchor_asset_type = \"crypto\"",
            "anchor_asset_type = \"crypto\"\nimage = \"http://$host/usdc.png\"",
          ),
        ),
        TomlCase(
          "trailing slash",
          "TRAILING_SLASH:KYC_SERVER",
          good.replace("https://$host/sep12", "https://$host/sep12/"),
        ),
        TomlCase(
          "ORG_URL on another domain",
          "ORG_URL_DOMAIN",
          good
            .replace("ORG_URL = \"https://$host\"", "ORG_URL = \"https://other.example.org\"")
            .replace("\nORG_PHYSICAL_ADDRESS_ATTESTATION = \"https://$host/address.pdf\"", ""),
        ),
        TomlCase(
          "attestation on another domain",
          "ATTESTATION_DOMAIN:ORG_PHYSICAL_ADDRESS_ATTESTATION",
          good.replace("https://$host/address.pdf", "https://other.example.org/address.pdf"),
        ),
      )

    cases.forEach { (name, expected, toml) ->
      assertEquals(listOf(expected), violations(toml), "case: $name")
    }
  }

  // SEP1TF-06, SEP1TF-08
  @ParameterizedTest
  @ValueSource(strings = ["localhost", "127.0.0.1", "host.docker.internal"])
  fun `test a local development host may use http and any ORG_URL`(local: String) {
    val http = good.replace("https://$host", "http://$local:8080")
    val otherOrgUrl =
      http.replace("ORG_URL = \"http://$local:8080\"", "ORG_URL = \"https://stellar.org\"")

    assertEquals(emptyList<String>(), violations(http, local))
    assertEquals(emptyList<String>(), violations(otherOrgUrl, local))
  }

  // SEP1TF-06
  @Test
  fun `test http URLs are refused on a host that is not a local development host`() {
    val http = good.replace("https://$host/sep", "http://$host/sep")

    assertEquals(
      listOf(
          "NOT_HTTPS:KYC_SERVER",
          "NOT_HTTPS:WEB_AUTH_FOR_CONTRACTS_ENDPOINT",
          "NOT_HTTPS:TRANSFER_SERVER",
          "NOT_HTTPS:TRANSFER_SERVER_SEP0024",
          "NOT_HTTPS:DIRECT_PAYMENT_SERVER",
          "NOT_HTTPS:ANCHOR_QUOTE_SERVER",
        )
        .sorted(),
      violations(http).sorted(),
    )
  }

  // SEP1TF-04: edge cases
  @Test
  fun `test the native currency, nft and a TOML without documentation are accepted`() {
    val nft = good.replaceFirst("anchor_asset_type = \"crypto\"", "anchor_asset_type = \"nft\"")
    val withoutDocumentation = good.substringBefore("[DOCUMENTATION]").trimEnd()

    assertEquals(emptyList<String>(), violations(nft))
    assertEquals(emptyList<String>(), violations(withoutDocumentation))
  }

  // SEP1TF-01: both official passphrases, and none
  @Test
  fun `test the public passphrase is accepted and a missing passphrase is not`() {
    assertEquals(
      emptyList<String>(),
      violations(
        good.replace(
          "Test SDF Network ; September 2015",
          "Public Global Stellar Network ; September 2015"
        )
      ),
    )
    assertEquals(
      listOf("NETWORK_PASSPHRASE"),
      violations(good.replace("NETWORK_PASSPHRASE = \"Test SDF Network ; September 2015\"\n", "")),
    )
  }

  // SEP1TF-02
  @Test
  fun `test a missing signing key and a key with a bad checksum are refused`() {
    assertEquals(
      listOf("SIGNING_KEY"),
      violations(good.replace("SIGNING_KEY = \"$signingKey\"\n", "")),
    )
    assertEquals(
      listOf("SIGNING_KEY"),
      violations(good.replace(signingKey, signingKey.dropLast(1) + "A")),
    )
  }

  // SEP1TF-04: the accepted side of every value set and the boundaries
  @Test
  fun `test every accepted status, asset type and code length passes`() {
    listOf("live", "dead", "test", "private").forEach { status ->
      assertEquals(
        emptyList<String>(),
        violations(good.replaceFirst("status = \"test\"", "status = \"$status\"")),
        "status $status",
      )
    }
    listOf("fiat", "crypto", "nft", "stock", "bond", "commodity", "realestate", "other").forEach {
      type ->
      assertEquals(
        emptyList<String>(),
        violations(
          good.replaceFirst("anchor_asset_type = \"crypto\"", "anchor_asset_type = \"$type\"")
        ),
        "type $type",
      )
    }
    assertEquals(
      emptyList<String>(),
      violations(good.replace("\"USDC\"", "\"ABCDEFGHIJKL\"")),
      "a 12 character code",
    )
    assertEquals(
      listOf("CURRENCY[0]:desc"),
      violations(good.replace("desc = \"Anchor Issued USDC Token\"", "desc = 5")),
    )
  }

  // SEP1TF-06 (size): exactly under and exactly at the bound
  @Test
  fun `test the size bound is 100000 bytes`() {
    val under = good + "\n" + "#".repeat(99_999 - good.length - 1)
    val at = good + "\n" + "#".repeat(100_000 - good.length - 1)

    assertEquals(99_999, under.toByteArray().size)
    assertEquals(100_000, at.toByteArray().size)
    assertEquals(emptyList<String>(), violations(under))
    assertEquals(listOf("SIZE"), violations(at))
  }

  private val topUrlFields =
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
  private val documentationUrlFields =
    listOf(
      "ORG_URL",
      "ORG_LOGO",
      "ORG_PHYSICAL_ADDRESS_ATTESTATION",
      "ORG_PHONE_NUMBER_ATTESTATION"
    )
  private val currencyUrlFields = listOf("image", "attestation_of_reserve", "approval_server")

  /** `good` with [field] set to [url], in the section the field belongs to. */
  private fun withUrl(field: String, url: String): String =
    when (field) {
      in topUrlFields ->
        good.replaceFirst("VERSION = \"0.1.0\"\n", "VERSION = \"0.1.0\"\n$field = \"$url\"\n").let {
          if (good.contains("\n$field = "))
            good.replace(Regex("\n$field = \"[^\"]*\""), "\n$field = \"$url\"")
          else it
        }
      in documentationUrlFields ->
        if (good.contains("\n$field = "))
          good.replace(Regex("\n$field = \"[^\"]*\""), "\n$field = \"$url\"")
        else good + "\n$field = \"$url\""
      else ->
        good.replaceFirst(
          "anchor_asset_type = \"crypto\"",
          "anchor_asset_type = \"crypto\"\n$field = \"$url\""
        )
    }

  private fun code(field: String) =
    if (field in currencyUrlFields) "CURRENCIES[0].$field" else field

  // SEP1TF-06, SEP1TF-07: every one of the 17 URL fields is checked
  @Test
  fun `test every URL field must be https and must not end with a slash`() {
    (topUrlFields + documentationUrlFields + currencyUrlFields).forEach { field ->
      assertEquals(
        listOf("NOT_HTTPS:${code(field)}"),
        violations(withUrl(field, "http://$host/x")),
        "http for $field",
      )
      assertEquals(
        listOf("TRAILING_SLASH:${code(field)}"),
        violations(withUrl(field, "https://$host/x/")),
        "trailing slash for $field",
      )
      assertEquals(
        emptyList<String>(),
        violations(withUrl(field, "https://$host/x")),
        "https without a slash for $field",
      )
    }
  }

  // SEP1TF-06: the local-host exception is an exact host match
  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "notlocalhost",
        "localhost.evil.com",
        "127.0.0.10",
        "host.docker.internal.evil.com",
        "xlocalhost",
        "evil.localhost",
        "x.host.docker.internal",
      ]
  )
  fun `test a host that only resembles a local development host gets no exception`(
    lookalike: String
  ) {
    val http = good.replace("https://$host", "http://$lookalike")

    assertTrue(violations(http, lookalike).contains("NOT_HTTPS:KYC_SERVER"), lookalike)
  }

  // SEP1TF-05
  @Test
  fun `test a linked TOML with a trailing slash is refused`() {
    assertEquals(
      listOf("LINKED_TOML[2]:URL"),
      violations(good + "\n[[CURRENCIES]]\ntoml = \"https://$host/USDC.toml/\"\n"),
    )
  }

  // SEP1TF-08: look-alike suffixes, subdomains (accepted, spec A13) and the phone attestation
  @Test
  fun `test the attestation domain must be the ORG_URL domain or a subdomain of it`() {
    assertEquals(
      listOf("ATTESTATION_DOMAIN:ORG_PHYSICAL_ADDRESS_ATTESTATION"),
      violations(good.replace("https://$host/address.pdf", "https://evil$host/address.pdf")),
    )
    assertEquals(
      emptyList<String>(),
      violations(good.replace("https://$host/address.pdf", "https://docs.$host/address.pdf")),
    )
    assertEquals(
      listOf("ATTESTATION_DOMAIN:ORG_PHONE_NUMBER_ATTESTATION"),
      violations(withUrl("ORG_PHONE_NUMBER_ATTESTATION", "https://other.example.org/phone.pdf")),
    )
  }

  private val withoutAttestation =
    good.replace("\nORG_PHYSICAL_ADDRESS_ATTESTATION = \"https://$host/address.pdf\"", "")

  // SEP1TF-08: the ORG_URL host is the host the TOML is served from, exactly
  @ParameterizedTest
  @ValueSource(
    strings = ["x.anchor.example.com", "evilanchor.example.com", "anchor.example.com.evil.com"]
  )
  fun `test the ORG_URL host must equal the served host exactly`(orgHost: String) {
    val toml =
      withoutAttestation.replace("ORG_URL = \"https://$host\"", "ORG_URL = \"https://$orgHost\"")

    assertEquals(listOf("ORG_URL_DOMAIN"), violations(toml, host), orgHost)
  }

  // SEP1TF-08: an ORG_URL without a parseable host is not on the served domain
  @Test
  fun `test an ORG_URL without a parseable host is refused`() {
    val toml =
      withoutAttestation.replace("ORG_URL = \"https://$host\"", "ORG_URL = \"https://bad_host\"")

    assertEquals(listOf("ORG_URL_DOMAIN"), violations(toml, host))
  }

  // SEP1TF-08: an attestation host that merely contains the ORG_URL host is not on its domain
  @Test
  fun `test an attestation host that contains the ORG_URL host as a prefix or middle is refused`() {
    listOf("x.anchor.example.com.evil.com", "anchor.example.com.evil.com").forEach { attestationHost
      ->
      assertEquals(
        listOf("ATTESTATION_DOMAIN:ORG_PHYSICAL_ADDRESS_ATTESTATION"),
        violations(
          good.replace("https://$host/address.pdf", "https://$attestationHost/address.pdf")
        ),
        attestationHost,
      )
    }
  }

  // SEP1TF-04: every required currency field, on any entry
  @Test
  fun `test a currency missing a required field is refused, on any entry`() {
    listOf(
        "code = \"USDC\"\n" to "code",
        "status = \"test\"\n" to "status",
        "is_asset_anchored = false\n" to "is_asset_anchored",
        "anchor_asset_type = \"crypto\"\n" to "anchor_asset_type",
      )
      .forEach { (line, field) ->
        assertEquals(
          listOf("CURRENCY[0]:$field"),
          violations(good.replaceFirst(line, "")),
          "currency 0 without $field",
        )
      }
    assertEquals(
      listOf("CURRENCY[1]:status"),
      violations(
        good.replace(
          "status = \"test\"\nis_asset_anchored = false\nanchor_asset_type = \"crypto\"\ndesc = \"XLM",
          "status = \"foo\"\nis_asset_anchored = false\nanchor_asset_type = \"crypto\"\ndesc = \"XLM"
        )
      ),
    )
    assertEquals(
      listOf("NOT_HTTPS:CURRENCIES[1].image"),
      violations(
        good.replace(
          "anchor_asset_type = \"crypto\"\ndesc = \"XLM",
          "anchor_asset_type = \"crypto\"\nimage = \"http://$host/xlm.png\"\ndesc = \"XLM"
        )
      ),
    )
  }

  // SEP1TF-04, SEP1TF-05: an issuer with a bad checksum and a non-string link
  @Test
  fun `test an issuer with a bad checksum and a non-string linked TOML are refused`() {
    assertEquals(
      listOf("CURRENCY[0]:issuer"),
      violations(good.replace(issuer, issuer.dropLast(1) + "A")),
    )
    assertEquals(
      listOf("LINKED_TOML[2]:URL"),
      violations(good + "\n[[CURRENCIES]]\ntoml = 5\n"),
    )
  }

  // SEP1TF-06: only http or https, never another scheme, even on a local host
  @Test
  fun `test another scheme is refused even on a local development host`() {
    val ftp =
      good
        .replace("https://$host/sep12", "ftp://localhost:8080/sep12")
        .replace("https://$host", "http://localhost:8080")

    assertEquals(listOf("NOT_HTTPS:KYC_SERVER"), violations(ftp, "localhost"))
  }

  private fun repoFile(relative: String): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
      if (Files.exists(dir.resolve(relative))) return dir.resolve(relative)
      dir = dir.parent
    }
    fail<Unit>("$relative not found above ${System.getProperty("user.dir")}")
    throw IllegalStateException()
  }

  // SEP1TF-11: each template is checked against the host its own WEB_AUTH_ENDPOINT names.
  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "service-runner/src/main/resources/config/stellar.localhost.toml",
        "service-runner/src/main/resources/config/stellar.host.docker.internal.toml",
        "docs/resources/deployment-examples/example-fargate/example_config/stellar.toml",
      ]
  )
  fun `test a TOML shipped for operators follows every rule`(relative: String) {
    val toml = Files.readString(repoFile(relative))
    val servedFrom = URI(Toml().read(toml).getString("WEB_AUTH_ENDPOINT")).host

    assertEquals(emptyList<String>(), violations(toml, servedFrom), relative)
  }
}
