package org.stellar.anchor.platform.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DepositInfoSelfGeneratorBaseTest {

  /** Exposes the protected `generateMemoId` for direct testing. */
  private class Probe : DepositInfoSelfGeneratorBase() {
    fun generate(): String = generateMemoId()
  }

  @Test
  fun `memo is a parseable positive long`() {
    val probe = Probe()
    repeat(1000) {
      val memo = probe.generate()
      val parsed = memo.toLongOrNull()
      assertNotNull(parsed, "memo $memo not parseable as Long")
      assertTrue(parsed!! > 0, "memo $parsed must be > 0")
    }
  }

  @Test
  fun `concurrent generation produces no collisions`() {
    val probe = Probe()
    val threads = 64
    val perThread = 2_000
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val seen = ConcurrentHashMap.newKeySet<String>()

    repeat(threads) {
      pool.submit {
        start.await()
        repeat(perThread) { seen.add(probe.generate()) }
      }
    }

    start.countDown()
    pool.shutdown()
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "generator pool did not finish in time")

    assertEquals(
      threads * perThread,
      seen.size,
      "expected all memos to be unique under concurrent load"
    )
  }
}
