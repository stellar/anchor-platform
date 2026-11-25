package org.stellar.anchor.util

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.io.CompressionAlgorithm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.util.JwtUtil.RefusedCompression
import org.stellar.anchor.util.JwtUtil.jwtsBuilder
import org.stellar.anchor.util.JwtUtil.jwtsParser

internal class JwtUtilTest {
  private val key = KeyUtil.toSecretKeySpecOrNull("jwt_util_test_secret_32_characters_long")

  @Test
  fun `a signed zip DEF bomb is refused instead of inflated`() {
    val inflated = "A".repeat(5_000_000)
    val bomb =
      jwtsBuilder()
        .claim("pad", inflated)
        .compressWith(Jwts.ZIP.DEF)
        .signWith(key, Jwts.SIG.HS256)
        .compact()
    assertTrue(bomb.length <= 8_192, "bomb is ${bomb.length} characters")

    val ex = assertThrows<UnsupportedJwtException> { parse(bomb) }
    assertEquals("Compressed JWTs are not supported", ex.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["DEF", "GZIP"])
  fun `refusing a signed bomb allocates far less than its inflated size`(zipId: String) {
    // Black-box check through jjwt's own payload stream: inflating the 5,000,000-byte payload
    // allocates at least that much, whatever the decompress implementation looks like.
    val bomb =
      jwtsBuilder()
        .claim("pad", "A".repeat(5_000_000))
        .compressWith(Jwts.ZIP.get().forKey(zipId))
        .signWith(key, Jwts.SIG.HS256)
        .compact()
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    assertTrue(threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled)
    val thread = Thread.currentThread().id
    assertThrows<UnsupportedJwtException> { parse(bomb) } // warm-up: class loading, parser setup

    val before = threads.getThreadAllocatedBytes(thread)
    val ex = assertThrows<UnsupportedJwtException> { parse(bomb) }
    val allocated = threads.getThreadAllocatedBytes(thread) - before

    assertEquals("Compressed JWTs are not supported", ex.message)
    assertTrue(allocated < 1_000_000, "refusal allocated $allocated bytes")
  }

  @ParameterizedTest
  @ValueSource(strings = ["DEF", "GZIP"])
  fun `the refused compression reads no byte of the compressed payload`(zipId: String) {
    // A real deflated payload, so an implementation that inflates before refusing reads it.
    val compressed =
      ByteArrayOutputStream().also { out ->
        Jwts.ZIP.get().forKey(zipId).compress(out).use { it.write(ByteArray(1_000_000)) }
      }
    val payload = CountingInputStream(compressed.toByteArray())

    val ex = assertThrows<UnsupportedJwtException> { RefusedCompression(zipId).decompress(payload) }
    assertEquals("Compressed JWTs are not supported", ex.message)
    assertEquals(0, payload.bytesRead)
  }

  @ParameterizedTest
  @ValueSource(strings = ["FOO", "def"])
  fun `a zip header value other than DEF or GZIP is refused`(zipId: String) {
    val identity =
      object : CompressionAlgorithm {
        override fun getId() = zipId

        override fun compress(out: OutputStream) = out

        override fun decompress(input: InputStream) = input
      }
    val token =
      jwtsBuilder().subject("sub").compressWith(identity).signWith(key, Jwts.SIG.HS256).compact()

    assertThrows<UnsupportedJwtException> { parse(token) }
  }

  private fun parse(token: String) = jwtsParser().verifyWith(key).build().parse(token)

  // Extends InputStream, not ByteArrayInputStream: InputStream's bulk methods (readAllBytes,
  // readNBytes, transferTo, skip) all go through the two read methods below, so none escapes the
  // count. mark/reset/available pass through so the stream looks like the ByteArrayInputStream
  // jjwt hands to decompress, and a refusal that inflates only on such a stream is still caught.
  private class CountingInputStream(bytes: ByteArray) : InputStream() {
    private val source = ByteArrayInputStream(bytes)
    var bytesRead = 0L

    override fun read(): Int = source.read().also { if (it >= 0) bytesRead++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      source.read(b, off, len).also { if (it > 0) bytesRead += it }

    override fun available(): Int = source.available()

    override fun markSupported(): Boolean = source.markSupported()

    override fun mark(readlimit: Int) = source.mark(readlimit)

    override fun reset() = source.reset()
  }
}
