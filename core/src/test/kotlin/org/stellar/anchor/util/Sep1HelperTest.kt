package org.stellar.anchor.util

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.InvalidConfigException

class Sep1HelperTest {

  private fun dottedChain(segments: Int, segmentName: String = "s"): String =
    (1..segments).joinToString(".") { "$segmentName$it" }

  @Test
  fun `test table header with 21 segments is rejected`() {
    val toml = "[${dottedChain(21)}]\nleaf = \"value\"\n"
    assertThrows(InvalidConfigException::class.java) { Sep1Helper.parse(toml) }
  }

  @Test
  fun `test array-of-tables header with 21 segments is rejected`() {
    val toml = "[[${dottedChain(21)}]]\nleaf = \"value\"\n"
    assertThrows(InvalidConfigException::class.java) { Sep1Helper.parse(toml) }
  }

  @Test
  fun `test table header with exactly 20 segments parses`() {
    val toml = "[${dottedChain(20)}]\nleaf = \"ok\"\n"
    val content = Sep1Helper.parse(toml)
    assertTrue(content.getString("${dottedChain(20)}.leaf") == "ok")
  }

  @Test
  fun `test reporter PoC-shaped header is rejected quickly without invoking toml4j`() {
    // Mirrors the report's [a.a.a....a] shape: ~20,000 segments inside a document under the
    // existing 100 KB download cap. Without the guard, toml4j's quadratic path construction over
    // this many segments is what exhausts the heap (confirmed directly against the bundled jar
    // under -Xmx256m: OutOfMemoryError in ~100ms); with the guard, rejection must be immediate.
    val toml = "[${dottedChain(20_000, "a")}]\nSIGNING_KEY = \"GA...\"\n"
    val start = System.currentTimeMillis()
    assertThrows(InvalidConfigException::class.java) { Sep1Helper.parse(toml) }
    val elapsedMs = System.currentTimeMillis() - start
    assertTrue(
      elapsedMs < 1000,
      "rejection took ${elapsedMs}ms; the guard should reject before toml4j ever parses the document"
    )
  }

  @Test
  fun `test a quoted segment in a header with literal dots counts as one segment`() {
    // A quoted table-header segment may itself contain dots without splitting into more segments.
    val toml = "[\"a.b.c\".leaf]\nkey = \"value\"\n"
    val content = Sep1Helper.parse(toml)
    assertTrue(content.getString("\"a.b.c\".leaf.key") == "value")
  }

  @Test
  fun `test a quoted segment with many internal dots does not push a legitimate header over the limit`() {
    // 18 plain segments + 1 quoted segment (19 real segments, under the limit) whose quoted text
    // alone contains 5 literal dots. If those internal dots were ever counted toward the segment
    // total, the miscounted total would be 18 dots (s1..s18) + 1 connecting dot + 5 internal dots =
    // 24 dots -> 25 segments, well over the limit, and this header would be wrongly rejected.
    val quotedSegment = "\"${(1..6).joinToString(".") { "x" }}\""
    val toml = "[${dottedChain(18)}.$quotedSegment]\nleaf = \"ok\"\n"
    val content = Sep1Helper.parse(toml)
    assertTrue(content.getString("${dottedChain(18)}.$quotedSegment.leaf") == "ok")
  }

  @Test
  fun `test a deeply dotted header is rejected regardless of overall document size`() {
    val small = "[${dottedChain(21)}]\n"
    assertThrows(InvalidConfigException::class.java) { Sep1Helper.parse(small) }
  }

  @Test
  fun `test bare dotted key-value line is rejected by toml4j itself, not by the guard`() {
    // toml4j 0.7.2 does not support TOML's dotted-key syntax at all (added to the TOML spec after
    // this version shipped) and rejects it immediately on its own, confirmed directly against the
    // bundled jar: an 20,000-segment bare dotted key fails in ~20ms with no OutOfMemoryError. The
    // guard does not need to scan key-value lines; this test pins that the existing failure mode
    // (a parse error, obfuscated the same way as any other) still holds.
    val toml = "${dottedChain(21)} = \"value\"\n"
    assertThrows(InvalidConfigException::class.java) { Sep1Helper.parse(toml) }
  }

  @Test
  fun `test empty document parses unchanged`() {
    Sep1Helper.parse("")
  }

  @Test
  fun `test a single quoted key with many internal dots is not rejected`() {
    val toml = "\"${(1..50).joinToString(".") { "a" }}\" = \"value\"\n"
    val content = Sep1Helper.parse(toml)
    assertTrue(content.getString("\"${(1..50).joinToString(".") { "a" }}\"") == "value")
  }

  @Test
  fun `test the committed test_stellar toml fixture parses unchanged`() {
    val tomlText =
      Sep1HelperTest::class.java.classLoader.getResource("test_stellar.toml")!!.readText()
    val content = Sep1Helper.parse(tomlText)
    assertTrue(
      content.getString("SIGNING_KEY") == "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
    )
    assertTrue(content.getString("DOCUMENTATION.ORG_NAME") == "Stellar Development Foundation")
  }

  @Test
  fun `test deeply nested inline arrays raise StackOverflowError that is caught and obfuscated`() {
    // Reported independently by the HackerOne reporter while validating the segment-depth guard:
    // nesting (not dotted keys) also drives toml4j into unbounded recursion, this time exhausting
    // the stack rather than the heap. Confirmed directly against the bundled jar: 50,000 levels of
    // nested arrays reliably throws StackOverflowError. Sep1Helper must convert it into the same
    // InvalidConfigException as any other malformed document, not let it escape as an Error.
    val depth = 50_000
    val toml = "a = " + "[".repeat(depth) + "1" + "]".repeat(depth) + "\n"
    assertThrows(InvalidConfigException::class.java) { Sep1Helper.parse(toml) }
  }

  @Test
  fun `test currencies documentation and principals tables parse unchanged`() {
    val toml =
      """
        |[[CURRENCIES]]
        |code = "USDC"
        |issuer = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        |
        |[DOCUMENTATION]
        |ORG_NAME = "Stellar Development Foundation"
        |
        |[PRINCIPALS]
        |name = "Jane Doe"
        """
        .trimMargin()
    val content = Sep1Helper.parse(toml)
    assertTrue(content.getString("DOCUMENTATION.ORG_NAME") == "Stellar Development Foundation")
  }

  @Test
  fun `test top-level keys only parse unchanged`() {
    val toml =
      """
        |SIGNING_KEY = "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
        |NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"
        """
        .trimMargin()
    val content = Sep1Helper.parse(toml)
    assertTrue(
      content.getString("SIGNING_KEY") == "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
    )
  }
}
