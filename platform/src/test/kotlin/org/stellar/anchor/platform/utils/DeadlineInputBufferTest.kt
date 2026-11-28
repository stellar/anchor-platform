package org.stellar.anchor.platform.utils

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.apache.coyote.InputBuffer
import org.apache.tomcat.util.net.ApplicationBufferHandler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.utils.RequestBodyReadDeadlineValve.DeadlineInputBuffer
import org.stellar.anchor.platform.utils.RequestBodyReadDeadlineValve.RequestBodyReadTimeoutException

/**
 * The deadline is 30 seconds. `now` is the injected clock in nanoseconds; the buffer starts its
 * clock when it is built, at `now == 0`.
 */
class DeadlineInputBufferTest {
  private val deadlineNanos = TimeUnit.SECONDS.toNanos(30)
  private var now = 0L
  private var delegateReads = 0

  /** Hands out the given chunks one per read, then end of stream; `onRead` runs on every read. */
  private inner class Source(
    chunks: List<ByteArray>,
    private val endless: Boolean = false,
    private val onRead: () -> Unit = {},
  ) : InputBuffer {
    private val queue = ArrayDeque(chunks)

    override fun doRead(handler: ApplicationBufferHandler): Int {
      delegateReads++
      onRead()
      val chunk = if (endless) ByteArray(DRAIN_CHUNK) else queue.removeFirstOrNull() ?: return -1
      val target = handler.byteBuffer
      target.clear()
      target.put(chunk)
      target.flip()
      return chunk.size
    }

    override fun available(): Int = 0
  }

  private class Handler : ApplicationBufferHandler {
    private var buffer: ByteBuffer = ByteBuffer.allocate(DRAIN_CHUNK)

    override fun setByteBuffer(buffer: ByteBuffer) {
      this.buffer = buffer
    }

    override fun getByteBuffer(): ByteBuffer = buffer

    override fun expand(size: Int) {}
  }

  private fun buffer(source: InputBuffer, contentLength: Long) =
    DeadlineInputBuffer(source, contentLength, deadlineNanos) { now }

  private fun DeadlineInputBuffer.readOne(handler: Handler = Handler()): Int = doRead(handler)

  @Test
  fun `a read after the deadline of a body that is not finished throws the timeout`() {
    val body = buffer(Source(listOf(byteArrayOf(1), byteArrayOf(2))), contentLength = 5000)
    assertEquals(1, body.readOne())

    now = deadlineNanos + 1

    assertThrows(RequestBodyReadTimeoutException::class.java) { body.readOne() }
  }

  @Test
  fun `a read after the deadline does not touch the source`() {
    val body = buffer(Source(listOf(byteArrayOf(1), byteArrayOf(2))), contentLength = 5000)
    assertEquals(1, body.readOne())
    now = deadlineNanos + 1
    val readsBefore = delegateReads

    assertThrows(RequestBodyReadTimeoutException::class.java) { body.readOne() }

    assertEquals(readsBefore, delegateReads)
  }

  @Test
  fun `the deadline counts from when the buffer was built`() {
    now = 1_000 * deadlineNanos
    val body = buffer(Source(listOf(byteArrayOf(1), byteArrayOf(2))), contentLength = 5000)

    now += deadlineNanos
    assertEquals(1, body.readOne())

    now += 1
    assertThrows(RequestBodyReadTimeoutException::class.java) { body.readOne() }
  }

  @Test
  fun `a read that returns data after the deadline passed during it throws the timeout`() {
    val body =
      buffer(
        Source(listOf(byteArrayOf(1))) { now = deadlineNanos + 1 },
        contentLength = 5000,
      )

    assertThrows(RequestBodyReadTimeoutException::class.java) { body.readOne() }
  }

  @Test
  fun `bytes that arrive before the deadline are delivered unchanged`() {
    val chunks = listOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5, 6))
    val body = buffer(Source(chunks), contentLength = 6)
    val handler = Handler()
    val received = mutableListOf<Byte>()

    while (true) {
      now += TimeUnit.SECONDS.toNanos(1)
      val n = body.doRead(handler)
      if (n == -1) break
      repeat(n) { received.add(handler.byteBuffer.get()) }
    }

    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), received.toByteArray())
    assertTrue(body.finished())
  }

  @Test
  fun `a read exactly at the deadline succeeds and one nanosecond later throws`() {
    val atDeadline = buffer(Source(listOf(byteArrayOf(1), byteArrayOf(2))), contentLength = 5000)
    now = deadlineNanos
    assertEquals(1, atDeadline.readOne())

    now = deadlineNanos + 1
    assertThrows(RequestBodyReadTimeoutException::class.java) { atDeadline.readOne() }
  }

  @Test
  fun `the last bytes of a body that arrive after the deadline still pass`() {
    val body =
      buffer(Source(listOf(byteArrayOf(1, 2, 3))) { now = deadlineNanos + 1 }, contentLength = 3)

    assertEquals(3, body.readOne())
    assertTrue(body.finished())
  }

  @Test
  fun `a body without a length is finished only at end of stream`() {
    val body = buffer(Source(listOf(byteArrayOf(1))), contentLength = -1)
    assertEquals(1, body.readOne())
    assertFalse(body.finished())

    now = deadlineNanos + 1
    assertThrows(RequestBodyReadTimeoutException::class.java) { body.readOne() }
  }

  @Test
  fun `end of stream of a body without a length at the deadline passes`() {
    val body = buffer(Source(listOf(byteArrayOf(1))), contentLength = -1)
    assertEquals(1, body.readOne())

    now = deadlineNanos
    assertEquals(-1, body.readOne())
    assertTrue(body.finished())
  }

  @Test
  fun `a request has a body when it declares a length or a transfer encoding`() {
    assertTrue(RequestBodyReadDeadlineValve.hasBody(5000, null))
    assertTrue(RequestBodyReadDeadlineValve.hasBody(-1, "chunked"))
    assertFalse(RequestBodyReadDeadlineValve.hasBody(0, null))
    assertFalse(RequestBodyReadDeadlineValve.hasBody(-1, null))
  }

  @Test
  fun `a body the application read in full is not drained`() {
    val body = buffer(Source(listOf(byteArrayOf(1, 2, 3))), contentLength = 3)
    assertEquals(3, body.readOne())
    val readsBefore = delegateReads

    val discarded =
      RequestBodyReadDeadlineValve.drain(body, RequestBodyReadDeadlineValve.MAX_DRAIN_BYTES)

    assertEquals(0, discarded)
    assertEquals(readsBefore, delegateReads)
  }

  @Test
  fun `the drain discards the rest of a body that finishes`() {
    val body =
      buffer(Source(listOf(byteArrayOf(1), byteArrayOf(2, 3), byteArrayOf(4))), contentLength = 4)
    assertEquals(1, body.readOne())

    val discarded =
      RequestBodyReadDeadlineValve.drain(body, RequestBodyReadDeadlineValve.MAX_DRAIN_BYTES)

    assertEquals(3, discarded)
    assertTrue(body.finished())
  }

  @Test
  fun `the drain stops once two mebibytes have been discarded`() {
    val body = buffer(Source(emptyList(), endless = true), contentLength = 100L * 1024 * 1024)

    val discarded =
      RequestBodyReadDeadlineValve.drain(body, RequestBodyReadDeadlineValve.MAX_DRAIN_BYTES)

    val cap = 2L * 1024 * 1024
    assertEquals(cap, RequestBodyReadDeadlineValve.MAX_DRAIN_BYTES)
    assertTrue(discarded >= cap && discarded < cap + DRAIN_CHUNK, "discarded $discarded")
    assertFalse(body.finished())
  }

  @Test
  fun `the drain throws the timeout once the deadline passes`() {
    val body = buffer(Source(listOf(byteArrayOf(1), byteArrayOf(2))), contentLength = 5000)
    now = deadlineNanos + 1

    assertThrows(RequestBodyReadTimeoutException::class.java) {
      RequestBodyReadDeadlineValve.drain(body, RequestBodyReadDeadlineValve.MAX_DRAIN_BYTES)
    }
  }

  private companion object {
    const val DRAIN_CHUNK = 8192
  }
}
