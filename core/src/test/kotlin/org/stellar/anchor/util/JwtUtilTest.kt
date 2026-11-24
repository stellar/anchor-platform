package org.stellar.anchor.util

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.io.CompressionAlgorithm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
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
  // count.
  private class CountingInputStream(bytes: ByteArray) : InputStream() {
    private val source = ByteArrayInputStream(bytes)
    var bytesRead = 0L

    override fun read(): Int = source.read().also { if (it >= 0) bytesRead++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      source.read(b, off, len).also { if (it > 0) bytesRead += it }
  }
}
