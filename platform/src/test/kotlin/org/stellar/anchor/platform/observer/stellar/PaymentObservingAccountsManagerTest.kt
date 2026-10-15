@file:Suppress("unused")

package org.stellar.anchor.platform.observer.stellar

import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.ChronoUnit.HOURS
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.stellar.anchor.platform.data.PaymentObservingAccount
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager.AccountType.RESIDENTIAL
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager.AccountType.TRANSIENT
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount

class PaymentObservingAccountsManagerTest {
  private val paymentObservingAccountStore = MemoryPaymentObservingAccountStore()
  private val testAcct1 = KeyPair.random().accountId
  private val testAcct2 = KeyPair.random().accountId
  private val testAcct3 = KeyPair.random().accountId
  private val testAcct4 = KeyPair.random().accountId
  private val testMuxAcct100 = MuxedAccount(testAcct4, BigInteger("100")).address
  private val testMuxAcct200 = MuxedAccount(testAcct4, BigInteger("200")).address

  @Test
  fun `test add and lookup`() {
    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    obs.upsert(testAcct1, TRANSIENT)
    obs.upsert(testAcct2, TRANSIENT)
    obs.upsert(testAcct3, TRANSIENT)
    obs.upsert(testAcct4, TRANSIENT)

    assertEquals(4, obs.accounts.size)

    assertTrue(obs.lookupAndUpdate(testAcct1))
    assertTrue(obs.lookupAndUpdate(testAcct2))
    assertTrue(obs.lookupAndUpdate(testAcct3))
    assertTrue(obs.lookupAndUpdate(testMuxAcct100))
    assertTrue(obs.lookupAndUpdate(testMuxAcct200))
  }

  @Test
  fun `test add duplicates`() {
    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    assertEquals(0, obs.accounts.size)
    obs.upsert(testAcct1, TRANSIENT)
    assertDoesNotThrow { obs.upsert(testAcct1, TRANSIENT) }
    assertEquals(1, obs.accounts.size)
  }

  @Test
  fun `test eviction`() {
    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    obs.upsert(testAcct1, TRANSIENT)
    assertEquals(1, obs.accounts.size)
    obs.evict(Duration.of(1, DAYS))
    // Nothing evict-able
    assertEquals(1, obs.accounts.size)

    obs.upsert(
      PaymentObservingAccountsManager.ObservingAccount(
        testAcct2,
        Instant.now().minus(24, HOURS),
        TRANSIENT
      )
    )

    obs.upsert(
      PaymentObservingAccountsManager.ObservingAccount(
        testAcct3,
        Instant.now().minus(48, HOURS),
        TRANSIENT
      )
    )

    obs.upsert(
      PaymentObservingAccountsManager.ObservingAccount(
        testMuxAcct100,
        Instant.now().minus(100, DAYS),
        RESIDENTIAL
      )
    )

    assertEquals(4, obs.accounts.size)

    // RESIDENTIAL accounts should not be evicted
    obs.evict(Duration.of(50, HOURS))
    assertEquals(4, obs.accounts.size)

    // Evict TRANSIENT accounts older than 47 hours
    obs.evict(Duration.of(47, HOURS))
    assertEquals(3, obs.accounts.size)
    assertFalse(obs.lookupAndUpdate(testAcct3))

    // Update the last observed timestamp to avoid being evicted
    assertTrue(obs.lookupAndUpdate(testAcct1))
    assertTrue(obs.lookupAndUpdate(testAcct2))
    obs.evict(Duration.of(10, HOURS))
    assertEquals(3, obs.accounts.size)

    assertTrue(obs.lookupAndUpdate(testAcct1))
    assertTrue(obs.lookupAndUpdate(testAcct2))
    assertTrue(obs.lookupAndUpdate(testMuxAcct100))

    // Evict all transient accounts
    obs.evict(Duration.ZERO)
    assertEquals(1, obs.accounts.size)
    assertTrue(obs.lookupAndUpdate(testMuxAcct100))
  }

  @Test
  fun `test muxed account`() {
    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    obs.upsert(testAcct4, TRANSIENT)
    assertTrue(obs.lookupAndUpdate(testMuxAcct100))
    assertTrue(obs.lookupAndUpdate(testMuxAcct200))
  }

  @Test
  fun `test registering by muxed account is found by the base account`() {
    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    obs.upsert(testMuxAcct100, TRANSIENT)
    assertEquals(1, obs.accounts.size)

    assertTrue(obs.lookupAndUpdate(testAcct4))
    assertTrue(obs.lookupAndUpdate(testMuxAcct100))
    assertTrue(obs.lookupAndUpdate(testMuxAcct200))
  }

  @Test
  fun `test loading a legacy muxed row canonicalizes and deletes the stale row`() {
    paymentObservingAccountStore.upsert(testMuxAcct100, Instant.now())
    assertEquals(1, paymentObservingAccountStore.list().size)
    assertEquals(testMuxAcct100, paymentObservingAccountStore.list()[0].account)

    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    val persisted = paymentObservingAccountStore.list()
    assertEquals(1, persisted.size)
    assertEquals(testAcct4, persisted[0].account)
    assertTrue(obs.lookupAndUpdate(testAcct4))
  }

  @Test
  fun `test upsert does not throw when the store fails to persist`() {
    val obs = PaymentObservingAccountsManager(ThrowingPaymentObservingAccountStore())
    obs.initialize()

    assertDoesNotThrow { obs.upsert(testAcct1, TRANSIENT) }
    assertEquals(1, obs.accounts.size)
    assertTrue(obs.lookupAndUpdate(testAcct1))

    assertDoesNotThrow { obs.evict(Duration.ZERO) }
    assertEquals(0, obs.accounts.size)
  }

  @Test
  fun `test legacy muxed row is not deleted when the canonical write fails`() {
    val store = FlakyPaymentObservingAccountStore()
    store.upsert(testMuxAcct100, Instant.now())
    assertEquals(1, store.list().size)

    store.failNextUpsert = true
    val obs = PaymentObservingAccountsManager(store)
    obs.initialize()

    val persisted = store.list()
    assertEquals(1, persisted.size)
    assertEquals(testMuxAcct100, persisted[0].account)
    assertTrue(obs.lookupAndUpdate(testAcct4))
  }

  @Test
  fun `test a stale duplicate does not clobber a newer lastObserved timestamp`() {
    val obs = PaymentObservingAccountsManager(paymentObservingAccountStore)
    obs.initialize()

    val newer = Instant.now()
    val older = newer.minusSeconds(60)

    obs.upsert(PaymentObservingAccountsManager.ObservingAccount(testAcct4, newer, TRANSIENT))
    obs.upsert(PaymentObservingAccountsManager.ObservingAccount(testMuxAcct100, older, TRANSIENT))

    assertEquals(1, obs.accounts.size)
    assertEquals(newer, obs.accounts[0].lastObserved)
  }

  @Test
  fun `test concurrent upserts never lose the newest lastObserved under race`() {
    val obs = PaymentObservingAccountsManager(NoopPaymentObservingAccountStore())
    obs.initialize()

    val threadCount = 32
    val perThread = 500
    val pool = Executors.newFixedThreadPool(threadCount)
    val start = CountDownLatch(1)
    val base = Instant.now()

    try {
      repeat(threadCount) { threadIndex ->
        pool.submit {
          start.await()
          for (i in 0 until perThread) {
            val ts = base.plusNanos((threadIndex.toLong() * perThread + i) * 1000)
            obs.upsert(PaymentObservingAccountsManager.ObservingAccount(testAcct1, ts, TRANSIENT))
          }
        }
      }
      start.countDown()
      pool.shutdown()
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "upsert pool did not finish in time")
    } finally {
      pool.shutdownNow()
    }

    val expectedMax = base.plusNanos((threadCount.toLong() * perThread - 1) * 1000)
    val finalAccount = obs.accounts.first { it.account == testAcct1 }
    assertEquals(expectedMax, finalAccount.lastObserved)
  }

  private fun staleAccount(
    account: String,
    idleDays: Long = 31,
    type: PaymentObservingAccountsManager.AccountType = TRANSIENT
  ) =
    PaymentObservingAccountsManager.ObservingAccount(
      account,
      Instant.now().minus(idleDays, DAYS),
      type
    )

  @Test
  fun `test evict keeps an account re-armed by upsert after the snapshot`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    val snapshot = obs.accounts
    val snapshotLastObserved = snapshot[0].lastObserved

    obs.upsert(testAcct1, TRANSIENT)
    obs.snapshot = snapshot
    obs.evict(Duration.of(30, DAYS))

    assertEquals(1, obs.accounts.size)
    assertTrue(obs.accounts[0].lastObserved.isAfter(snapshotLastObserved))
    assertTrue(obs.lookupAndUpdate(testAcct1))
    assertEquals(emptyList<String>(), store.deleted)
    assertEquals(listOf(testAcct1), store.list().map { it.account })
  }

  @Test
  fun `test evict keeps an account re-armed by lookupAndUpdate after the snapshot`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    val snapshot = obs.accounts

    assertTrue(obs.lookupAndUpdate(testAcct1))
    obs.snapshot = snapshot
    obs.evict(Duration.of(30, DAYS))

    assertEquals(1, obs.accounts.size)
    assertEquals(emptyList<String>(), store.deleted)
    assertEquals(listOf(testAcct1), store.list().map { it.account })
  }

  @Test
  fun `test evict removes an idle account untouched since the snapshot`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    obs.snapshot = obs.accounts

    obs.evict(Duration.of(30, DAYS))

    assertEquals(0, obs.accounts.size)
    assertFalse(obs.lookupAndUpdate(testAcct1))
    assertEquals(listOf(testAcct1), store.deleted)
    assertEquals(0, store.list().size)
  }

  @Test
  fun `test evict keeps an account idle for less than maxIdleTime and evicts one idle for more`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = PaymentObservingAccountsManager(store)
    obs.initialize()
    val maxIdle = Duration.of(30, DAYS)
    obs.upsert(
      PaymentObservingAccountsManager.ObservingAccount(
        testAcct1,
        Instant.now().minus(maxIdle).plus(1, ChronoUnit.MINUTES),
        TRANSIENT
      )
    )
    obs.upsert(
      PaymentObservingAccountsManager.ObservingAccount(
        testAcct2,
        Instant.now().minus(maxIdle).minus(1, ChronoUnit.MINUTES),
        TRANSIENT
      )
    )

    obs.evict(maxIdle)

    assertEquals(listOf(testAcct1), obs.accounts.map { it.account })
    assertEquals(listOf(testAcct2), store.deleted)
  }

  @Test
  fun `test evict never removes a residential account however long idle`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1, idleDays = 400, type = RESIDENTIAL))
    obs.snapshot = obs.accounts

    obs.evict(Duration.of(30, DAYS))

    assertEquals(1, obs.accounts.size)
    assertTrue(obs.lookupAndUpdate(testAcct1))
    assertEquals(emptyList<String>(), store.deleted)
  }

  @Test
  fun `test evict skips only the re-armed account when two idle accounts are in the snapshot`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    obs.upsert(staleAccount(testAcct2))
    val snapshot = obs.accounts

    obs.upsert(testAcct1, TRANSIENT)
    obs.snapshot = snapshot
    obs.evict(Duration.of(30, DAYS))

    assertEquals(listOf(testAcct1), obs.accounts.map { it.account })
    assertEquals(listOf(testAcct2), store.deleted)
  }

  @Test
  fun `test evict does not delete the row of an account another caller already removed`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    obs.snapshot = obs.accounts

    obs.allAccounts.remove(testAcct1)
    obs.evict(Duration.of(30, DAYS))

    assertEquals(emptyList<String>(), store.deleted)
    assertEquals(listOf(testAcct1), store.list().map { it.account })
  }

  @Test
  fun `test concurrent lookupAndUpdate during evict never drops an account that was re-armed`() {
    val rounds = 30
    val accountCount = 200
    val lookupThreads = 32
    val pool = Executors.newFixedThreadPool(lookupThreads + 1)

    try {
      repeat(rounds) {
        val store = RecordingPaymentObservingAccountStore()
        val obs = PaymentObservingAccountsManager(store)
        obs.initialize()
        val accounts = List(accountCount) { KeyPair.random().accountId }
        accounts.forEach { obs.upsert(staleAccount(it)) }

        val rearmed = ConcurrentHashMap.newKeySet<String>()
        val start = CountDownLatch(1)
        val futures = mutableListOf<Future<*>>()
        repeat(lookupThreads) {
          futures.add(
            pool.submit {
              start.await()
              accounts.shuffled().forEach { if (obs.lookupAndUpdate(it)) rearmed.add(it) }
            }
          )
        }
        futures.add(
          pool.submit {
            start.await()
            obs.evict(Duration.of(30, DAYS))
          }
        )
        start.countDown()
        futures.forEach { assertDoesNotThrow { it.get(30, TimeUnit.SECONDS) } }

        val live = obs.accounts.map { it.account }.toSet()
        val persisted = store.list().map { it.account }.toSet()
        rearmed.forEach {
          assertTrue(live.contains(it), "re-armed account $it was dropped from the live map")
          assertTrue(persisted.contains(it), "re-armed account $it lost its durable row")
        }
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `test evict continues with the remaining accounts when a store delete throws`() {
    val store = DeleteFailingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    obs.upsert(staleAccount(testAcct2))
    obs.snapshot = obs.accounts

    assertDoesNotThrow { obs.evict(Duration.of(30, DAYS)) }

    assertEquals(0, obs.accounts.size)
    assertEquals(setOf(testAcct1, testAcct2), store.deleteAttempts.toSet())
  }

  @Test
  fun `test ObservingAccount is compared by identity so a re-armed entry never matches the snapshot`() {
    val lastObserved = Instant.now()
    val a = PaymentObservingAccountsManager.ObservingAccount(testAcct1, lastObserved, TRANSIENT)
    val b = PaymentObservingAccountsManager.ObservingAccount(testAcct1, lastObserved, TRANSIENT)

    assertNotEquals(a, b)
    assertEquals(a, a)
  }

  @Test
  fun `test evictAndPersist persists every account still in the live map after eviction`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = PaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    obs.upsert(testAcct2, TRANSIENT)
    obs.upsert(staleAccount(testAcct3, type = RESIDENTIAL))

    obs.evictAndPersist()

    assertEquals(setOf(testAcct2, testAcct3), obs.accounts.map { it.account }.toSet())
    assertEquals(setOf(testAcct2, testAcct3), store.list().map { it.account }.toSet())
  }

  @Test
  fun `test evict keeps an account re-armed several times after the snapshot`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    val snapshot = obs.accounts

    obs.upsert(testAcct1, TRANSIENT)
    assertTrue(obs.lookupAndUpdate(testAcct1))
    obs.upsert(testAcct1, TRANSIENT)
    obs.snapshot = snapshot
    obs.evict(Duration.of(30, DAYS))

    assertEquals(listOf(testAcct1), obs.accounts.map { it.account })
    assertEquals(emptyList<String>(), store.deleted)
  }

  @Test
  fun `test evict keeps the canonical entry when the account is re-armed through a muxed address`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct4))
    val snapshot = obs.accounts

    obs.upsert(testMuxAcct100, TRANSIENT)
    obs.snapshot = snapshot
    obs.evict(Duration.of(30, DAYS))

    assertEquals(listOf(testAcct4), obs.accounts.map { it.account })
    assertTrue(obs.lookupAndUpdate(testMuxAcct200))
    assertFalse(store.deleted.contains(testAcct4))
  }

  @Test
  fun `test evict does nothing for an empty snapshot`() {
    val store = RecordingPaymentObservingAccountStore()
    val obs = SnapshotPaymentObservingAccountsManager(store)
    obs.initialize()
    obs.upsert(staleAccount(testAcct1))
    obs.snapshot = emptyList()

    assertDoesNotThrow { obs.evict(Duration.of(30, DAYS)) }

    assertEquals(1, obs.accounts.size)
    assertEquals(emptyList<String>(), store.deleted)
  }
}

/**
 * Models the eviction snapshot: the next [getAccounts] call returns [snapshot] once, then clears
 * it.
 */
class SnapshotPaymentObservingAccountsManager(store: PaymentObservingAccountStore) :
  PaymentObservingAccountsManager(store) {
  var snapshot: List<ObservingAccount>? = null

  override fun getAccounts(): List<ObservingAccount> {
    val pending = snapshot
    snapshot = null
    return pending ?: super.getAccounts()
  }
}

class RecordingPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
  private val accounts = java.util.concurrent.CopyOnWriteArrayList<PaymentObservingAccount>()
  val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()

  public override fun list(): List<PaymentObservingAccount> = accounts

  public override fun upsert(account: String?, lastObserved: Instant?) {
    accounts.removeIf { it.account == account }
    accounts.add(PaymentObservingAccount(account, lastObserved))
  }

  public override fun delete(account: String) {
    deleted.add(account)
    accounts.removeIf { it.account == account }
  }
}

class MemoryPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
  private val accounts = mutableListOf<PaymentObservingAccount>()

  public override fun list(): List<PaymentObservingAccount> = accounts

  public override fun upsert(account: String?, lastObserved: Instant?) {
    accounts.removeIf { it.account == account }
    accounts.add(PaymentObservingAccount(account, lastObserved))
  }

  public override fun delete(account: String) {
    accounts.removeIf { it.account == account }
  }
}

class ThrowingPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
  override fun list(): List<PaymentObservingAccount> = emptyList()

  override fun upsert(account: String?, lastObserved: Instant?) {
    throw RuntimeException("store unavailable")
  }

  override fun delete(account: String) {
    throw RuntimeException("store unavailable")
  }
}

class NoopPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
  override fun list(): List<PaymentObservingAccount> = emptyList()

  override fun upsert(account: String?, lastObserved: Instant?) {}

  override fun delete(account: String) {}
}

class FlakyPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
  private val accounts = mutableListOf<PaymentObservingAccount>()
  var failNextUpsert = false

  public override fun list(): List<PaymentObservingAccount> = accounts

  public override fun upsert(account: String?, lastObserved: Instant?) {
    if (failNextUpsert) {
      failNextUpsert = false
      throw RuntimeException("store unavailable")
    }
    accounts.removeIf { it.account == account }
    accounts.add(PaymentObservingAccount(account, lastObserved))
  }

  public override fun delete(account: String) {
    accounts.removeIf { it.account == account }
  }
}

class DeleteFailingPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
  val deleteAttempts = java.util.concurrent.CopyOnWriteArrayList<String>()

  override fun list(): List<PaymentObservingAccount> = emptyList()

  override fun upsert(account: String?, lastObserved: Instant?) {}

  override fun delete(account: String) {
    deleteAttempts.add(account)
    throw RuntimeException("store unavailable")
  }
}
