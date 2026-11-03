package org.stellar.anchor.platform.observer.stellar

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.platform.component.observer.PaymentObserverBeans
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager.AccountType.RESIDENTIAL
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager.AccountType.TRANSIENT
import org.stellar.sdk.KeyPair

class DistributionAccountSeedTest {
  private val distribution1 = KeyPair.random().accountId
  private val distribution2 = KeyPair.random().accountId

  private fun asset(distributionAccount: String) =
    StellarAssetInfo().also { it.distributionAccount = distributionAccount }

  /** Builds a manager the way a restart does: rows in the store are loaded as TRANSIENT. */
  private fun managerAfterRestart(
    store: RecordingPaymentObservingAccountStore
  ): PaymentObservingAccountsManager {
    val manager = PaymentObservingAccountsManager(store)
    manager.initialize()
    return manager
  }

  private fun PaymentObservingAccountsManager.typeOf(account: String) =
    accounts.first { it.account == account }.type

  @Test
  fun `test seed re-types a distribution account loaded from the store as TRANSIENT`() {
    val store = RecordingPaymentObservingAccountStore()
    store.upsert(distribution1, Instant.now().minus(400, DAYS))
    val manager = managerAfterRestart(store)
    assertEquals(TRANSIENT, manager.typeOf(distribution1))

    PaymentObserverBeans.seedDistributionAccounts(manager, listOf(asset(distribution1)))

    assertEquals(RESIDENTIAL, manager.typeOf(distribution1))
  }

  @Test
  fun `test a seeded distribution account survives an evict of every non-RESIDENTIAL account`() {
    val store = RecordingPaymentObservingAccountStore()
    store.upsert(distribution1, Instant.now().minus(400, DAYS))
    val manager = managerAfterRestart(store)
    PaymentObserverBeans.seedDistributionAccounts(manager, listOf(asset(distribution1)))

    manager.evict(Duration.ZERO)

    assertEquals(listOf(distribution1), manager.accounts.map { it.account })
    assertTrue(manager.lookupAndUpdate(distribution1))
    assertEquals(emptyList<String>(), store.deleted)
  }

  @Test
  fun `test seed adds an absent distribution account as RESIDENTIAL on first boot`() {
    val manager = managerAfterRestart(RecordingPaymentObservingAccountStore())

    PaymentObserverBeans.seedDistributionAccounts(manager, listOf(asset(distribution1)))

    assertEquals(1, manager.accounts.size)
    assertEquals(RESIDENTIAL, manager.typeOf(distribution1))
    val lastObserved = manager.accounts.first { it.account == distribution1 }.lastObserved
    assertTrue(Duration.between(lastObserved, Instant.now()).abs() < Duration.ofMinutes(1))
  }

  @Test
  fun `test seed keeps a RESIDENTIAL account RESIDENTIAL and never moves lastObserved back`() {
    val manager = managerAfterRestart(RecordingPaymentObservingAccountStore())
    val newer = Instant.now().plus(1, DAYS)
    manager.upsert(
      PaymentObservingAccountsManager.ObservingAccount(distribution1, newer, RESIDENTIAL)
    )

    PaymentObserverBeans.seedDistributionAccounts(manager, listOf(asset(distribution1)))

    assertEquals(RESIDENTIAL, manager.typeOf(distribution1))
    assertEquals(newer, manager.accounts.first { it.account == distribution1 }.lastObserved)
  }

  @Test
  fun `test seed registers each distinct distribution account once as RESIDENTIAL`() {
    val store = RecordingPaymentObservingAccountStore()
    store.upsert(distribution2, Instant.now().minus(400, DAYS))
    val manager = managerAfterRestart(store)

    PaymentObserverBeans.seedDistributionAccounts(
      manager,
      listOf(asset(distribution1), asset(distribution2), asset(distribution1))
    )

    assertEquals(setOf(distribution1, distribution2), manager.accounts.map { it.account }.toSet())
    assertEquals(2, manager.accounts.size)
    assertEquals(RESIDENTIAL, manager.typeOf(distribution1))
    assertEquals(RESIDENTIAL, manager.typeOf(distribution2))
  }
}
