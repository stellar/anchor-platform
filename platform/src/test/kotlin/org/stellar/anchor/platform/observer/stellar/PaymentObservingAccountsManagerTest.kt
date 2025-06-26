@file:Suppress("unused")

package org.stellar.anchor.platform.observer.stellar

import org.junit.jupiter.api.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.ChronoUnit.HOURS
import org.junit.jupiter.api.Assertions.*
import org.stellar.anchor.platform.data.PaymentObservingAccount
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager.AccountType.RESIDENTIAL
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager.AccountType.TRANSIENT
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount
import java.math.BigInteger

class PaymentObservingAccountsManagerTest {
    private val paymentObservingAccountStore = MemoryPaymentObservingAccountStore()
    private val testAcct1 = KeyPair.random().accountId
    private val testAcct2 = KeyPair.random().accountId
    private val testAcct3 = KeyPair.random().accountId
    private val testAcct4 = KeyPair.random().accountId
    private val testMuxAcct100 = MuxedAccount(testAcct4, BigInteger("100")).accountId
    private val testMuxAcct200 = MuxedAccount(testAcct4, BigInteger("100")).accountId

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
        assertDoesNotThrow {
            obs.upsert(testAcct1, TRANSIENT)
        }
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
}

class MemoryPaymentObservingAccountStore : PaymentObservingAccountStore(null) {
    private val accounts = mutableListOf<PaymentObservingAccount>()

    override fun list(): List<PaymentObservingAccount> = accounts

    override fun upsert(account: String?, lastObserved: Instant?) {
        accounts.removeIf { it.account == account }
        accounts.add(PaymentObservingAccount(account, lastObserved))
    }

    override fun delete(account: String) {
        accounts.removeIf { it.account == account }
    }
}