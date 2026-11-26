package org.stellar.anchor.util

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException

/**
 * HackerOne #4234405: the mainnet client_domain guard accepted 240.0.0.0/4 (Class E), so an
 * unauthenticated request made the SEP server connect there. Each test pins one acceptance
 * criterion of the spec; the table itself is walked in [SpecialPurposeAddressRangesTest].
 */
class ClientDomainClassESsrfTest {
  private val nonPublic = "client_domain resolves to a non-public address"
  private var originalDns: Dns = ClientDomainHelper.dns

  @BeforeEach
  fun saveDns() {
    originalDns = ClientDomainHelper.dns
  }

  @AfterEach
  fun restoreDns() {
    ClientDomainHelper.dns = originalDns
  }

  private fun assertRefused(clientDomain: String) {
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.validateDomainNotPrivateNetwork(clientDomain)
      }
    assertEquals(nonPublic, ex.message, "$clientDomain must be refused as non-public")
  }

  private fun stubDns(vararg answers: String) {
    ClientDomainHelper.dns =
      object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
          answers.map { InetAddress.getByName(it) }
      }
  }

  // CDSSRF-01
  @Test
  fun `Class E IPv4 literals are refused`() {
    assertRefused("240.0.0.5")
    assertRefused("255.255.255.254")
  }

  // CDSSRF-01
  @Test
  fun `the limited broadcast address is refused`() {
    assertRefused("255.255.255.255")
  }

  // CDSSRF-02
  @Test
  fun `a Class E literal with a port is refused`() {
    assertRefused("240.0.0.5:8443")
  }

  // CDSSRF-02
  @Test
  fun `a host name resolving to Class E is refused`() {
    stubDns("240.0.0.9")
    assertRefused("class-e.attacker.example")
  }

  // CDSSRF-02
  @Test
  fun `a host name with one public and one Class E answer is refused`() {
    stubDns("93.184.216.34", "240.0.0.9")
    assertRefused("mixed.attacker.example")
  }

  // CDSSRF-03
  @Test
  fun `mainnet fetch of a Class E literal is refused by the guard before any request`() {
    // The guard's own message, not "Unable to read client_domain's SIGNING_KEY", shows that the
    // request was never built.
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain("240.0.0.5", false)
      }
    assertEquals(nonPublic, ex.message)
  }

  // CDSSRF-03
  @Test
  fun `the DNS of the mainnet fetch client refuses a Class E answer`() {
    stubDns("240.0.0.9")
    assertThrows(UnknownHostException::class.java) {
      ClientDomainHelper.validatingDns().lookup("class-e.attacker.example")
    }
  }

  // CDSSRF-04
  @Test
  fun `an IPv4-compatible IPv6 literal embedding Class E is refused`() {
    assertRefused("::f000:5")
  }

  // Edge case: Java turns an IPv4-mapped literal into the IPv4 address
  @Test
  fun `an IPv4-mapped IPv6 literal embedding Class E is refused`() {
    assertRefused("::ffff:240.0.0.5")
  }

  // CDSSRF-10 (SEP-10 and SEP-45 both call this entry point)
  @Test
  fun `the bounded entry point shared by SEP-10 and SEP-45 refuses Class E with a port`() {
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomainBounded("240.0.0.5:8443", false)
      }
    assertEquals(nonPublic, ex.message)
  }

  // CDSSRF-11
  @Test
  fun `off mainnet the guard does not refuse a Class E host`() {
    stubDns("240.0.0.9")
    // The fetch itself fails (nothing answers), but not with the guard's refusal.
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain("class-e.test.example", true)
      }
    assertNotEquals(nonPublic, ex.message)
  }

  // CDSSRF-08
  @Test
  fun `ranges refused before the table are still refused`() {
    for (h in
      listOf(
        "0.0.0.0",
        "0.1.2.3",
        "100.127.255.255",
        "198.19.255.255",
        "192.0.0.1",
        "169.254.169.254",
        "fe80::1",
        "::",
        "64:ff9b::a00:1",
        "64:ff9b:1::a00:1",
        "2002:a00:1::",
        "::10.0.0.1",
        "::ffff:10.0.0.1"
      )) {
      assertRefused(h)
    }
  }

  // CDSSRF-09
  @Test
  fun `addresses next to refused ranges stay accepted`() {
    for (h in
      listOf("100.63.255.255", "100.128.0.0", "198.17.255.255", "198.20.0.0", "::1:a00:1")) {
      assertDoesNotThrow { ClientDomainHelper.validateDomainNotPrivateNetwork(h) }
    }
  }

  // CDSSRF-09
  @Test
  fun `a failed lookup is still reported as unresolvable`() {
    ClientDomainHelper.dns =
      object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
          throw UnknownHostException(hostname)
      }
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.validateDomainNotPrivateNetwork("gone.attacker.example")
      }
    assertEquals("client_domain could not be resolved", ex.message)
  }
}
