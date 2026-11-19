package org.stellar.anchor.util

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.util.BoundedDnsResolver.Lookup
import org.stellar.anchor.util.BoundedDnsResolver.ResolutionUnavailableException

class BoundedDnsResolverTest {

  private val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

  /** Blocks like a native resolver call: an interrupt does not release it, only [release] does. */
  private fun blockIgnoringInterrupts(release: CountDownLatch) {
    var interrupted = false
    while (true) {
      try {
        release.await()
        break
      } catch (e: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  @Test
  fun `a lookup that outlasts the timeout makes resolve fail while the lookup keeps running`() {
    val release = CountDownLatch(1)
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val resolver =
      BoundedDnsResolver(
        Lookup {
          started.countDown()
          blockIgnoringInterrupts(release)
          finished.countDown()
          arrayOf(publicAddress)
        },
        2,
        200,
      )
    try {
      val start = System.nanoTime()
      val ex =
        assertThrows<ResolutionUnavailableException> { resolver.resolve("stuck.example.com") }
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

      assertEquals("client_domain resolution unavailable", ex.message)
      assertTrue(elapsedMs < 700, "expected failure within timeout + 500ms, took ${elapsedMs}ms")
      assertTrue(started.await(1, TimeUnit.SECONDS))
      assertEquals(1, finished.count, "the lookup must still be running after the caller gave up")
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `resolve fails closed when every resolver worker is held by a stuck lookup`() {
    val release = CountDownLatch(1)
    val invoked = CopyOnWriteArrayList<String>()
    val resolver =
      BoundedDnsResolver(
        Lookup { host ->
          invoked.add(host)
          blockIgnoringInterrupts(release)
          arrayOf(publicAddress)
        },
        2,
        200,
      )
    try {
      assertThrows<ResolutionUnavailableException> { resolver.resolve("a.example.com") }
      assertThrows<ResolutionUnavailableException> { resolver.resolve("b.example.com") }

      val start = System.nanoTime()
      val ex = assertThrows<ResolutionUnavailableException> { resolver.resolve("c.example.com") }
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

      assertEquals("client_domain resolution unavailable", ex.message)
      assertTrue(elapsedMs < 150, "expected immediate rejection, took ${elapsedMs}ms")
      assertFalse(invoked.contains("c.example.com"), "no worker was free, so no lookup may start")
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `a lookup that completes in time returns exactly the stub's addresses`() {
    val expected = arrayOf(publicAddress, InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
    val resolver = BoundedDnsResolver(Lookup { expected }, 2, 1_000)

    assertEquals(expected.toList(), resolver.resolve("ok.example.com"))
  }

  @Test
  fun `an UnknownHostException from the lookup propagates unchanged`() {
    val resolver = BoundedDnsResolver(Lookup { throw UnknownHostException("nxdomain") }, 2, 1_000)

    val ex = assertThrows<UnknownHostException> { resolver.resolve("missing.example.com") }

    assertEquals("nxdomain", ex.message)
    assertFalse(ex is ResolutionUnavailableException)
  }

  @Test
  fun `a runtime exception from the lookup becomes resolution unavailable`() {
    val resolver = BoundedDnsResolver(Lookup { throw IllegalStateException("boom") }, 2, 1_000)

    val ex = assertThrows<ResolutionUnavailableException> { resolver.resolve("boom.example.com") }

    assertEquals("client_domain resolution unavailable", ex.message)
  }

  @Test
  fun `an interrupted caller gets resolution unavailable and keeps its interrupt flag`() {
    val release = CountDownLatch(1)
    val started = CountDownLatch(1)
    val resolver =
      BoundedDnsResolver(
        Lookup {
          started.countDown()
          blockIgnoringInterrupts(release)
          arrayOf(publicAddress)
        },
        2,
        5_000,
      )
    val thrown = AtomicReference<Throwable>()
    val flagAfter = AtomicReference<Boolean>()
    val caller = Thread {
      try {
        resolver.resolve("slow.example.com")
      } catch (t: Throwable) {
        thrown.set(t)
        flagAfter.set(Thread.currentThread().isInterrupted)
      }
    }
    try {
      caller.start()
      assertTrue(started.await(1, TimeUnit.SECONDS))
      caller.interrupt()
      caller.join(2_000)

      assertFalse(caller.isAlive, "the interrupted caller must stop waiting")
      assertInstanceOf(ResolutionUnavailableException::class.java, thrown.get())
      assertEquals("client_domain resolution unavailable", thrown.get().message)
      assertEquals(true, flagAfter.get())
    } finally {
      release.countDown()
    }
  }
}
