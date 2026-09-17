@file:Suppress("unused")

package org.stellar.anchor.platform.observer.stellar

import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.ChronoUnit.HOURS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
