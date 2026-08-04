package org.stellar.anchor.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException

class ClientDomainHelperTest {

  @Test
  fun `test loopback address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("127.0.0.1")
    }
  }

  @Test
  fun `test localhost is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("localhost")
    }
  }

  @Test
  fun `test link-local address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("169.254.169.254")
    }
  }

  @Test
  fun `test private 10-x address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("10.0.0.1")
    }
  }

  @Test
  fun `test private 192-168 address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("192.168.1.1")
    }
  }

  @Test
  fun `test private 172-16 address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("172.16.0.1")
    }
  }

  @Test
  fun `test domain with port strips port before resolving`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("127.0.0.1:8080")
    }
  }

  @Test
  fun `test unresolvable domain is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork(
        "this-domain-does-not-exist-xyz123.invalid"
      )
    }
  }

  @Test
  fun `test public address is accepted`() {
    assertDoesNotThrow { ClientDomainHelper.validateDomainNotPrivateNetwork("8.8.8.8") }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain does not leak the attempted URL on failure`() {
    val domain = "this-domain-does-not-exist-xyz123.invalid"
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain(domain, true)
      }

    assertFalse(ex.message.orEmpty().contains(domain))
    assertFalse(ex.message.orEmpty().contains("http"))
  }

  @Test
  fun `test fetchSigningKeyFromClientDomainBounded still fails normally when the pool is not saturated`() {
    val domain = "this-domain-does-not-exist-xyz123.invalid"
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(domain, true)
      }
    assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
  }

  @Test
  fun `test fetchSigningKeyFromClientDomainBounded rejects fast when the bounded pool is saturated`() {
    val executor = ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR
    val release = CountDownLatch(1)
    val started = CountDownLatch(executor.maximumPoolSize)
    val blockers =
      (1..executor.maximumPoolSize).map {
        executor.submit {
          started.countDown()
          release.await()
        }
      }
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS))

      val start = System.currentTimeMillis()
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomainBounded("8.8.8.8", false)
        }
      assertEquals("client_domain resolution unavailable", ex.message)
      val elapsedMs = System.currentTimeMillis() - start
      assertTrue(elapsedMs < 2_500, "expected fast rejection, took ${elapsedMs}ms")
    } finally {
      release.countDown()
      blockers.forEach { it.get(5, TimeUnit.SECONDS) }
    }
  }

  @Test
  fun `test extractHostname with plain domain`() {
    assertEquals("example.com", ClientDomainHelper.extractHostname("example.com"))
  }

  @Test
  fun `test extractHostname with domain and port`() {
    assertEquals("example.com", ClientDomainHelper.extractHostname("example.com:8080"))
  }

  @Test
  fun `test extractHostname with bracketed IPv6`() {
    assertEquals("::1", ClientDomainHelper.extractHostname("[::1]"))
  }

  @Test
  fun `test extractHostname with bracketed IPv6 and port`() {
    assertEquals("::1", ClientDomainHelper.extractHostname("[::1]:8080"))
  }
}
