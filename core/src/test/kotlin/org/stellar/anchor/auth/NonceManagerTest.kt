package org.stellar.anchor.auth

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NonceManagerTest {

  @MockK(relaxed = true) lateinit var nonceStore: NonceStore
  private lateinit var clock: Clock

  private lateinit var nonceManager: NonceManager

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    clock = Clock.fixed(Instant.EPOCH, Clock.systemUTC().zone)

    every { nonceStore.newInstance() } answers { PojoNonce() }
    val nonce = slot<Nonce>()
    every { nonceStore.save(capture(nonce)) } answers { nonce.captured }

    nonceManager = NonceManager(nonceStore, clock)
  }

  @Test
  fun testGenerateNonce() {
    val nonce = nonceManager.create(300)

    assert(nonce.id.isNotEmpty())
    assertFalse(nonce.used)
    assert(nonce.expiresAt == Instant.EPOCH.plusSeconds(300))

    verify(exactly = 1) { nonceStore.save(nonce) }
  }

  @Test
  fun testGenerateUniqueNonces() {
    val nonce1 = nonceManager.create(300)
    val nonce2 = nonceManager.create(300)

    assertNotEquals(nonce1.id, nonce2.id)
  }

  @Test
  fun testConsumeUnusedNonce() {
    val nonce = nonceManager.create(300)

    every { nonceStore.findById(nonce.id) } returns nonce
    nonceManager.use(nonce.id)

    assertTrue(nonce.used)
    verify { nonceStore.save(nonce) }
  }

  @Test
  fun testConsumeMissingNonce() {
    every { nonceStore.findById(any()) } returns null
    assertThrows<RuntimeException> { nonceManager.use("123") }

    verify(exactly = 0) { nonceStore.save(any()) }
  }

  @Test
  fun testConsumeUsedNonce() {
    val nonce = PojoNonce()
    nonce.used = true

    every { nonceStore.findById(nonce.id) } returns nonce
    assertThrows<RuntimeException> { nonceManager.use(nonce.id) }

    verify(exactly = 0) { nonceStore.save(any()) }
  }

  @Test
  fun testVerifyMissingNonce() {
    every { nonceStore.findById(any()) } returns null
    assertFalse(nonceManager.verify("123"))
  }

  @Test
  fun testVerifyUsedNonce() {
    val nonce = PojoNonce()
    nonce.used = true

    every { nonceStore.findById(any()) } returns nonce
    assertFalse(nonceManager.verify("123"))
  }

  @Test
  fun testVerifyExpiredNonce() {
    val nonce = PojoNonce()
    nonce.used = false
    nonce.expiresAt = Instant.EPOCH.minusSeconds(1)

    every { nonceStore.findById(any()) } returns nonce
    assertFalse(nonceManager.verify("123"))
  }
}
