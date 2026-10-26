package org.stellar.reference.event.processor

import java.util.concurrent.ConcurrentHashMap

const val MAX_AUTO_ADVANCE_OPT_OUTS = 10_000

private val TRANSACTION_ID_REGEX =
  Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Platform transaction ids are `UUID.randomUUID().toString()`; anything else is not an id. */
fun isTransactionUuid(id: String): Boolean = TRANSACTION_ID_REGEX.matches(id)

/**
 * The ids a test has opted out of a processor's automatic advancement. Holds at most [capacity] ids
 * so a caller of the test-only `skip-auto-advance` route cannot grow it without limit.
 */
class AutoAdvanceOptOuts(private val capacity: Int = MAX_AUTO_ADVANCE_OPT_OUTS) {
  private val ids = ConcurrentHashMap.newKeySet<String>()
  private val lock = Any()

  /** Returns true if [id] is now (or already was) opted out, false if the set is full. */
  fun register(id: String): Boolean {
    if (ids.contains(id)) return true
    synchronized(lock) {
      if (ids.contains(id)) return true
      if (ids.size >= capacity) return false
      return ids.add(id)
    }
  }

  fun contains(id: String): Boolean = ids.contains(id)

  val size: Int
    get() = ids.size

  internal fun clear() = ids.clear()
}
