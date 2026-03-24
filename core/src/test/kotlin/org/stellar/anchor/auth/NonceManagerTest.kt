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
    every { nonceStore.findById(any()) } returns null
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
  fun testDuplicateNonceExists() {
    val nonce = PojoNonce()
    every { nonceStore.findById(any()) } returns nonce

    assertThrows<RuntimeException> { nonceManager.create(300) }

    verify(exactly = 1) { nonceStore.findById(any()) }
    verify(exactly = 0) { nonceStore.save(any()) }
  }

  @Test
  fun testVerifyAndUseSuccess() {
    every { nonceStore.markAsUsed("nonce-1", Instant.EPOCH) } returns 1
    assertTrue(nonceManager.verifyAndUse("nonce-1"))
  }

  @Test
  fun testVerifyAndUseAlreadyUsed() {
    every { nonceStore.markAsUsed("nonce-1", Instant.EPOCH) } returns 0
    assertFalse(nonceManager.verifyAndUse("nonce-1"))
  }

  @Test
  fun testVerifyAndUseNonexistent() {
    every { nonceStore.markAsUsed("missing", Instant.EPOCH) } returns 0
    assertFalse(nonceManager.verifyAndUse("missing"))
  }
}
