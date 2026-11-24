package org.stellar.anchor.util

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Resolver
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type

class BoundedDnsResolverTest {

  private open class StubResolver : Resolver {
    override fun setPort(port: Int) {}

    override fun setTCP(flag: Boolean) {}

    override fun setIgnoreTruncation(flag: Boolean) {}

    override fun setEDNS(
      version: Int,
      payloadSize: Int,
      flags: Int,
      options: MutableList<org.xbill.DNS.EDNSOption>?,
    ) {}

    override fun setTSIGKey(key: org.xbill.DNS.TSIG?) {}

    override fun setTimeout(timeout: Duration) {}

    override fun sendAsync(query: Message): CompletionStage<Message> = CompletableFuture()

    override fun sendAsync(query: Message, executor: Executor): CompletionStage<Message> =
      sendAsync(query)
  }

  private class AnsweringResolver(private val v4: InetAddress?, private val v6: InetAddress?) :
    StubResolver() {
    override fun sendAsync(query: Message): CompletionStage<Message> {
      val question = query.question
      val response = Message(query.header.id)
      response.header.setFlag(Flags.QR.toInt())
      response.header.setFlag(Flags.RA.toInt())
      response.addRecord(question, Section.QUESTION)
      when {
        question.type == Type.A && v4 != null ->
          response.addRecord(ARecord(question.name, DClass.IN, 60, v4), Section.ANSWER)
        question.type == Type.AAAA && v6 != null ->
          response.addRecord(AAAARecord(question.name, DClass.IN, 60, v6), Section.ANSWER)
        else -> response.header.rcode = Rcode.NOERROR
      }
      return CompletableFuture.completedFuture(response)
    }
  }

  @Test
  fun `test lookup against a nameserver that never answers fails within the timeout`() {
    DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
      val resolver =
        BoundedDnsResolver(
          300,
          SimpleResolver(InetSocketAddress(InetAddress.getLoopbackAddress(), silent.localPort)),
        )

      val start = System.nanoTime()
      assertThrows(UnknownHostException::class.java) {
        resolver.lookup("hang.unresponsive.example")
      }
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue(elapsedMs < 1_500, "expected a bounded lookup, took ${elapsedMs}ms")
    }
  }

  @Test
  fun `test lookup that never completes is bounded by the absolute timeout`() {
    val resolver = BoundedDnsResolver(300, StubResolver())

    val start = System.nanoTime()
    val ex = assertThrows(UnknownHostException::class.java) { resolver.lookup("hang.example.com") }
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    assertTrue(elapsedMs < 1_500, "expected a bounded lookup, took ${elapsedMs}ms")
    assertTrue(ex.message.orEmpty().contains("hang.example.com"))
  }

  @Test
  fun `test interrupting a pending lookup frees the thread`() {
    val resolver = BoundedDnsResolver(30_000, StubResolver())
    val executor = Executors.newSingleThreadExecutor()
    try {
      val started = CountDownLatch(1)
      val future =
        executor.submit<Boolean> {
          started.countDown()
          try {
            resolver.lookup("hang.example.com")
            false
          } catch (e: UnknownHostException) {
            Thread.currentThread().isInterrupted
          }
        }
      assertTrue(started.await(2, TimeUnit.SECONDS))
      Thread.sleep(100)

      val start = System.nanoTime()
      future.cancel(true)
      val idle = executor.submit<Boolean> { true }
      assertTrue(idle.get(2, TimeUnit.SECONDS))
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue(elapsedMs < 2_000, "expected the worker to be freed, took ${elapsedMs}ms")
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `test lookup returns both A and AAAA addresses`() {
    val v4 = InetAddress.getByName("93.184.216.34")
    val v6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")
    val resolver = BoundedDnsResolver(1_000, AnsweringResolver(v4, v6))

    val addresses = resolver.lookup("wallet.example.com")

    assertEquals(setOf(v4, v6), addresses.toSet())
  }

  @Test
  fun `test lookup returns the A address when there is no AAAA record`() {
    val v4 = InetAddress.getByName("93.184.216.34")
    val resolver = BoundedDnsResolver(1_000, AnsweringResolver(v4, null))

    assertEquals(listOf(v4), resolver.lookup("wallet.example.com"))
  }

  @Test
  fun `test lookup with no records fails`() {
    val resolver = BoundedDnsResolver(1_000, AnsweringResolver(null, null))

    assertThrows(UnknownHostException::class.java) { resolver.lookup("wallet.example.com") }
  }

  @Test
  fun `test IP literals are returned without a DNS query`() {
    val resolver = BoundedDnsResolver(1_000, StubResolver())

    assertEquals(listOf(InetAddress.getByName("8.8.8.8")), resolver.lookup("8.8.8.8"))
    assertEquals(listOf(InetAddress.getByName("::1")), resolver.lookup("::1"))
  }

  @Test
  fun `test malformed hostname fails`() {
    val resolver = BoundedDnsResolver(1_000, StubResolver())

    assertThrows(UnknownHostException::class.java) { resolver.lookup("a..b") }
  }
}
