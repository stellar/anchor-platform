package org.stellar.anchor.util

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
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

  private fun ms(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)

  /** A resolver whose lookup counts invocations and hangs on [stuckHosts] until [release]. */
  private class CountingLookup(
    private val release: CountDownLatch,
    private val stuckHosts: Set<String>,
    private val answer: (String) -> Array<InetAddress>,
  ) : Lookup {
    val invocations = CopyOnWriteArrayList<String>()

    override fun lookup(hostname: String): Array<InetAddress> {
      invocations.add(hostname)
      if (hostname in stuckHosts) {
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
      return answer(hostname)
    }
  }

  @Test
  fun `the defaults are a 30 second record of at most 1024 hostnames`() {
    assertEquals(30_000L, BoundedDnsResolver.DEFAULT_NEGATIVE_TTL_MS)
    assertEquals(1_024, BoundedDnsResolver.DEFAULT_NEGATIVE_CAPACITY)
  }

  @Test
  fun `a name that timed out is rejected fast on repeat without starting a lookup`() {
    val release = CountDownLatch(1)
    val lookup = CountingLookup(release, setOf("stuck.example.com")) { arrayOf(publicAddress) }
    val resolver = BoundedDnsResolver(lookup, 4, 200, 30_000, 1_024, LongSupplier { 0 })
    try {
      assertThrows<ResolutionUnavailableException> { resolver.resolve("stuck.example.com") }
      assertEquals(1, lookup.invocations.size)

      val start = System.nanoTime()
      val ex =
        assertThrows<ResolutionUnavailableException> { resolver.resolve("stuck.example.com") }
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

      assertEquals("client_domain resolution unavailable", ex.message)
      assertTrue(elapsedMs < 100, "expected fast rejection, took ${elapsedMs}ms")
      assertEquals(1, lookup.invocations.size, "the repeat must not start a lookup")
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `a timed out name is rejected until exactly 30000 ms have elapsed`() {
    val release = CountDownLatch(1)
    val first = AtomicInteger()
    val finished = CountDownLatch(1)
    val clock = AtomicLong()
    val lookup =
      CountingLookup(release, setOf("stuck.example.com")) {
        if (first.getAndIncrement() == 0) finished.countDown()
        arrayOf(publicAddress)
      }
    val resolver = BoundedDnsResolver(lookup, 4, 200, 30_000, 1_024, LongSupplier { clock.get() })
    try {
      assertThrows<ResolutionUnavailableException> { resolver.resolve("stuck.example.com") }
      release.countDown()
      assertTrue(finished.await(1, TimeUnit.SECONDS))

      clock.set(ms(29_999))
      assertThrows<ResolutionUnavailableException> { resolver.resolve("stuck.example.com") }
      assertEquals(1, lookup.invocations.size, "still recorded at 29,999 ms")

      clock.set(ms(30_000))
      assertEquals(listOf(publicAddress), resolver.resolve("stuck.example.com"))
      assertEquals(2, lookup.invocations.size, "a new lookup starts at 30,000 ms")
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `when the record is full a new timeout evicts only the oldest hostname`() {
    val release = CountDownLatch(1)
    val hosts = listOf("a.example.com", "b.example.com", "c.example.com", "d.example.com")
    val lookup = CountingLookup(release, hosts.toSet()) { arrayOf(publicAddress) }
    val resolver = BoundedDnsResolver(lookup, 8, 100, 30_000, 3, LongSupplier { 0 })
    try {
      // exactly at capacity: a, b, c are all recorded
      hosts.take(3).forEach {
        assertThrows<ResolutionUnavailableException> { resolver.resolve(it) }
      }
      hosts.take(3).forEach {
        assertThrows<ResolutionUnavailableException> { resolver.resolve(it) }
      }
      assertEquals(3, lookup.invocations.size, "a, b and c are rejected without new lookups")

      // one over capacity: d evicts a
      assertThrows<ResolutionUnavailableException> { resolver.resolve("d.example.com") }
      assertEquals(4, lookup.invocations.size)
      listOf("b.example.com", "c.example.com", "d.example.com").forEach {
        assertThrows<ResolutionUnavailableException> { resolver.resolve(it) }
      }
      assertEquals(4, lookup.invocations.size, "b, c and d are still rejected without lookups")

      // Let the stuck lookups return, so a repeat is judged by the record alone and does not join
      // a lookup that is still in flight.
      release.countDown()
      listOf("b.example.com", "c.example.com", "d.example.com").forEach {
        assertThrows<ResolutionUnavailableException> { resolver.resolve(it) }
      }
      assertEquals(4, lookup.invocations.size, "the record still rejects b, c and d")

      // a was evicted, so once its old lookup is gone it gets a new lookup and an answer.
      val deadline = System.nanoTime() + ms(2_000)
      while (lookup.invocations.size < 5 && System.nanoTime() < deadline) {
        assertEquals(listOf(publicAddress), resolver.resolve("a.example.com"))
        Thread.sleep(10)
      }
      assertEquals(5, lookup.invocations.size, "a was evicted, so it gets a new lookup")
      assertEquals("a.example.com", lookup.invocations.last())
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `an NXDOMAIN failure is not recorded`() {
    val calls = AtomicInteger()
    val resolver =
      BoundedDnsResolver(
        Lookup {
          calls.incrementAndGet()
          throw UnknownHostException("nxdomain")
        },
        2,
        1_000,
        30_000,
        1_024,
        LongSupplier { 0 },
      )

    assertThrows<UnknownHostException> { resolver.resolve("missing.example.com") }
    val ex = assertThrows<UnknownHostException> { resolver.resolve("missing.example.com") }

    assertEquals(2, calls.get())
    assertEquals("nxdomain", ex.message)
  }

  @Test
  fun `a successful lookup is not recorded`() {
    val calls = AtomicInteger()
    val resolver =
      BoundedDnsResolver(
        Lookup {
          calls.incrementAndGet()
          arrayOf(publicAddress)
        },
        2,
        1_000,
        30_000,
        1_024,
        LongSupplier { 0 },
      )

    assertEquals(listOf(publicAddress), resolver.resolve("ok.example.com"))
    assertEquals(listOf(publicAddress), resolver.resolve("ok.example.com"))

    assertEquals(2, calls.get())
  }

  @Test
  fun `a runtime failure is not recorded`() {
    val calls = AtomicInteger()
    val resolver =
      BoundedDnsResolver(
        Lookup {
          calls.incrementAndGet()
          throw IllegalStateException("boom")
        },
        2,
        1_000,
        30_000,
        1_024,
        LongSupplier { 0 },
      )

    assertThrows<ResolutionUnavailableException> { resolver.resolve("boom.example.com") }
    assertThrows<ResolutionUnavailableException> { resolver.resolve("boom.example.com") }

    assertEquals(2, calls.get())
  }

  @Test
  fun `concurrent resolutions of one name share a single lookup and its result`() {
    val release = CountDownLatch(1)
    val lookup = CountingLookup(release, setOf("shared.example.com")) { arrayOf(publicAddress) }
    val resolver = BoundedDnsResolver(lookup, 8, 5_000, 30_000, 1_024, LongSupplier { 0 })
    val callers = Executors.newFixedThreadPool(8)
    try {
      val results =
        (1..8).map { callers.submit<List<InetAddress>> { resolver.resolve("shared.example.com") } }
      Thread.sleep(300)
      release.countDown()

      results.forEach { assertEquals(listOf(publicAddress), it.get(2, TimeUnit.SECONDS)) }
      assertEquals(1, lookup.invocations.size, "8 concurrent callers must start exactly one lookup")
    } finally {
      release.countDown()
      callers.shutdownNow()
    }
  }

  @Test
  fun `concurrent resolutions of one stuck name occupy exactly one resolver worker`() {
    val release = CountDownLatch(1)
    val lookup = CountingLookup(release, setOf("stuck.example.com")) { arrayOf(publicAddress) }
    val resolver = BoundedDnsResolver(lookup, 2, 300, 30_000, 1_024, LongSupplier { 0 })
    val callers = Executors.newFixedThreadPool(8)
    try {
      val results =
        (1..8).map {
          callers.submit<Throwable?> {
            try {
              resolver.resolve("stuck.example.com")
              null
            } catch (t: Throwable) {
              t
            }
          }
        }
      results.forEach {
        assertInstanceOf(ResolutionUnavailableException::class.java, it.get(3, TimeUnit.SECONDS))
      }
      assertEquals(1, lookup.invocations.size, "8 callers, one lookup")

      // the pool has 2 workers; the stuck name holds one, so another name still gets the other
      assertEquals(listOf(publicAddress), resolver.resolve("other.example.com"))
    } finally {
      release.countDown()
      callers.shutdownNow()
    }
  }

  @Test
  fun `a lookup that finishes after its callers gave up is not shared once the record expires`() {
    val release = CountDownLatch(1)
    val clock = AtomicLong()
    val lookup = CountingLookup(release, setOf("late.example.com")) { arrayOf(publicAddress) }
    val resolver = BoundedDnsResolver(lookup, 4, 200, 30_000, 1_024, LongSupplier { clock.get() })
    try {
      assertThrows<ResolutionUnavailableException> { resolver.resolve("late.example.com") }
      release.countDown()
      clock.set(ms(30_000))

      // The in-flight entry is removed just after the stuck lookup returns, so allow a moment.
      val deadline = System.nanoTime() + ms(2_000)
      while (lookup.invocations.size < 2 && System.nanoTime() < deadline) {
        resolver.resolve("late.example.com")
        Thread.sleep(10)
      }

      assertEquals(2, lookup.invocations.size, "a fresh lookup starts once the old one finished")
    } finally {
      release.countDown()
    }
  }
}
