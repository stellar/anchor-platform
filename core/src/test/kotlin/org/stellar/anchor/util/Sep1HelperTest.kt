package org.stellar.anchor.util

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

  // ---- extractSigningKey (ANCHOR-1533): the client_domain path never hands the document to toml4j

  private val signingKey = "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"

  /** Each header shape from reports 4196233, 4217496, 4227934 and 4234079, `n` dotted repeats. */
  private fun attackHeaders(n: Int): Map<String, String> =
    mapOf(
      "unclosed quote [\"'" to "[\"'" + ".c".repeat(n) + "]",
      "unclosed quote [\"b'" to "[\"b'" + ".a".repeat(n) + "]",
      "escaped quote in the middle" to "[a.\"\\\"\"" + ".c".repeat(n) + "]",
      "mixed quotes '\"" to "[a.'\"" + ".c".repeat(n) + "]",
      "mixed quotes \"'" to "[a.\"'" + ".c".repeat(n) + "]",
      "leading escaped quote" to "[\"\\\"\"" + ".x".repeat(n) + "]",
      "escape before closing bracket" to "[\"a\\\"]\"" + ".x".repeat(n) + "]",
      "plain unquoted control" to "[a" + ".c".repeat(n) + "]",
      "plain array of tables" to "[[a" + ".c".repeat(n) + "]]",
      "unterminated quote" to "[a.\"" + ".c".repeat(n) + "]",
    )

  private fun inlineTableDocument(n: Int) = "x = {a" + ".a".repeat(n) + " = {}}\n"

  private fun crOnlyDocument(n: Int) = "x=1\r[a" + ".c".repeat(n) + "]\n"

  private fun elapsedMs(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000
  }

  @Test
  fun `test extractSigningKey returns the key whatever attack header follows it`() {
    for ((name, header) in attackHeaders(49_000)) {
      val doc = "SIGNING_KEY=\"$signingKey\"\n$header\n"
      assertTrue(doc.length in 98_000..100_300, "$name document is ${doc.length} bytes")
      var result: String? = null
      val ms = elapsedMs { result = Sep1Helper.extractSigningKey(doc) }
      assertEquals(signingKey, result, name)
      assertTrue(ms < 1000, "$name took ${ms}ms")
    }
  }

  @Test
  fun `test extractSigningKey returns null in under a second for every attack document without a key`() {
    val docs = attackHeaders(49_000).mapValues { it.value + "\n" }.toMutableMap()
    docs["inline table dotted key"] = inlineTableDocument(50_000)
    docs["CR-only line endings"] = crOnlyDocument(49_000)
    for ((name, doc) in docs) {
      assertTrue(doc.length in 98_000..100_300, "$name document is ${doc.length} bytes")
      var result: String? = "unset"
      val ms = elapsedMs { result = Sep1Helper.extractSigningKey(doc) }
      assertNull(result, name)
      assertTrue(ms < 1000, "$name took ${ms}ms")
    }
  }

  @Test
  fun `test extractSigningKey accepts every accepted key line form`() {
    val forms =
      listOf(
        "SIGNING_KEY=\"$signingKey\"",
        "SIGNING_KEY = \"$signingKey\"",
        "SIGNING_KEY\t=\t\"$signingKey\"",
        "SIGNING_KEY='$signingKey'",
        "SIGNING_KEY = '$signingKey'",
        "       SIGNING_KEY=\"$signingKey\"",
        "SIGNING_KEY = \"$signingKey\" # the signing key",
        "SIGNING_KEY = \"$signingKey\"#c",
        "SIGNING_KEY = \"$signingKey\"\r",
      )
    for (form in forms) {
      assertEquals(signingKey, Sep1Helper.extractSigningKey("$form\n"), form)
    }
  }

  @Test
  fun `test extractSigningKey returns after 8 concurrent attack documents in under 2 seconds`() {
    val docs = attackHeaders(49_000).values.take(8).map { "$it\n" }
    val pool = Executors.newFixedThreadPool(8)
    try {
      val start = System.nanoTime()
      val futures = docs.map { doc -> pool.submit<String?> { Sep1Helper.extractSigningKey(doc) } }
      val results = futures.map { it.get(10, TimeUnit.SECONDS) }
      val ms = (System.nanoTime() - start) / 1_000_000
      assertEquals(8, results.size)
      assertTrue(results.all { it == null })
      assertTrue(ms < 2000, "8 concurrent scans took ${ms}ms")
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `test extractSigningKey reads the committed test_stellar toml fixture`() {
    val tomlText =
      Sep1HelperTest::class.java.classLoader.getResource("test_stellar.toml")!!.readText()
    assertEquals(
      "GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR",
      Sep1Helper.extractSigningKey(tomlText)
    )
  }

  @Test
  fun `test extractSigningKey returns null when the key comes after a table header`() {
    val toml = "[DOCUMENTATION]\nSIGNING_KEY = \"$signingKey\"\n"
    assertNull(Sep1Helper.extractSigningKey(toml))
  }

  @Test
  fun `test extractSigningKey returns null when the first real line is a header even if the key follows`() {
    val toml = "# top comment\n\n   [X]\nSIGNING_KEY = \"$signingKey\"\n"
    assertNull(Sep1Helper.extractSigningKey(toml))
  }

  @Test
  fun `test extractSigningKey returns the key when it comes before the first header`() {
    val toml =
      "VERSION = \"0.1.0\"\nSIGNING_KEY = \"$signingKey\"\n[DOCUMENTATION]\nORG_NAME = \"x\"\n"
    assertEquals(signingKey, Sep1Helper.extractSigningKey(toml))
  }

  @Test
  fun `test extractSigningKey rejects a duplicate top-level SIGNING_KEY`() {
    val toml = "SIGNING_KEY = \"$signingKey\"\nSIGNING_KEY = \"$signingKey\"\n"
    val ex = assertThrows(InvalidConfigException::class.java) { Sep1Helper.extractSigningKey(toml) }
    assertEquals("Failed to parse TOML content. Invalid Config.", ex.message)
  }

  @Test
  fun `test extractSigningKey rejects every SIGNING_KEY value shape other than a single-line string`() {
    val q3 = "\"\"\""
    val s3 = "'''"
    val values =
      listOf(
        "SIGNING_KEY = $signingKey",
        "SIGNING_KEY = \"$signingKey",
        "SIGNING_KEY = '$signingKey",
        "SIGNING_KEY = $q3$signingKey$q3",
        "SIGNING_KEY = $s3$signingKey$s3",
        "SIGNING_KEY = \"GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4\\u0046\"",
        "SIGNING_KEY = \"$signingKey\" trailing",
        "SIGNING_KEY = \"$signingKey\"\"",
        "SIGNING_KEY =",
        "SIGNING_KEY = 5",
        "SIGNING_KEY = [\"$signingKey\"]",
      )
    for (line in values) {
      val ex =
        assertThrows(
          InvalidConfigException::class.java,
          { Sep1Helper.extractSigningKey("$line\n") },
          line
        )
      assertEquals("Failed to parse TOML content. Invalid Config.", ex.message, line)
    }
  }

  @Test
  fun `test extractSigningKey returns null for an empty document and for a document without the key`() {
    assertNull(Sep1Helper.extractSigningKey(""))
    assertNull(Sep1Helper.extractSigningKey("VERSION = \"0.1.0\"\nNETWORK_PASSPHRASE = \"x\"\n"))
  }

  @Test
  fun `test extractSigningKey ignores a commented SIGNING_KEY line`() {
    assertNull(Sep1Helper.extractSigningKey("# SIGNING_KEY = \"$signingKey\"\n"))
    assertEquals(
      signingKey,
      Sep1Helper.extractSigningKey("# SIGNING_KEY = \"OLD\"\nSIGNING_KEY = \"$signingKey\"\n")
    )
  }

  @Test
  fun `test extractSigningKey does not treat a longer or prefixed key name as SIGNING_KEY`() {
    val toml = "SIGNING_KEY_OTHER = \"OTHER\"\nXSIGNING_KEY = \"PREFIXED\"\nSIGNING_KEY2 = \"N\"\n"
    assertNull(Sep1Helper.extractSigningKey(toml))
    assertEquals(
      signingKey,
      Sep1Helper.extractSigningKey("SIGNING_KEY_OTHER = \"OTHER\"\nSIGNING_KEY = \"$signingKey\"\n")
    )
  }
}
