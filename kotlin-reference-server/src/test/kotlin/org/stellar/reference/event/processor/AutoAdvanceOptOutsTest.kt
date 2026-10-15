package org.stellar.reference.event.processor

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoAdvanceOptOutsTest {
  private fun uuid(i: Int) = "00000000-0000-4000-8000-%012x".format(i)

  @Test
  fun `the default capacity is 10000`() {
    assertEquals(10_000, MAX_AUTO_ADVANCE_OPT_OUTS)
    val set = AutoAdvanceOptOuts()
    repeat(10_000) { assertTrue(set.register(uuid(it))) }
    assertFalse(set.register(uuid(10_000)))
    assertEquals(10_000, set.size)
  }

  @Test
  fun `a registered id is accepted again when full and the size is unchanged`() {
    val set = AutoAdvanceOptOuts(3)
    repeat(3) { set.register(uuid(it)) }

    assertTrue(set.register(uuid(1)))
    assertFalse(set.register(uuid(3)))
    assertFalse(set.contains(uuid(3)))
    assertEquals(3, set.size)
  }

  @Test
  fun `concurrent registrations never exceed the capacity`() {
    val set = AutoAdvanceOptOuts(100)
    val accepted = AtomicInteger()
    val pool = Executors.newFixedThreadPool(16)
    val futures =
      (0 until 2_000).map { i ->
        pool.submit { if (set.register(uuid(i))) accepted.incrementAndGet() }
      }
    futures.forEach { it.get() }
    pool.shutdown()

    assertEquals(100, set.size)
    assertEquals(100, accepted.get())
  }

  @Test
  fun `only canonical uuids are transaction ids`() {
    assertTrue(isTransactionUuid("2a337bba-4280-41ee-8632-2f2752ee2a42"))
    assertTrue(isTransactionUuid("2A337BBA-4280-41EE-8632-2F2752EE2A42"))
    for (bad in
      listOf("", "txn-1", "1-1-1-1-1", "2a337bba-4280-41ee-8632-2f2752ee2a420", "A".repeat(3800))) {
      assertFalse(isTransactionUuid(bad), bad)
    }
  }
}
