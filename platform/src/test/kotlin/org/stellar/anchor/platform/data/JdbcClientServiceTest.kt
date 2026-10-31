package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.client.ClientConfig.ClientType
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.client.NonCustodialClient

class JdbcClientServiceTest {

  @MockK(relaxed = true) private lateinit var repo: JdbcClientConfigRepo

  private lateinit var service: JdbcClientService

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    service = JdbcClientService(repo)
  }

  private fun custodialRow(name: String, signingKeys: Set<String>) =
    JdbcClientConfig().apply {
      this.name = name
      this.type = ClientType.CUSTODIAL
      this.signingKeys = signingKeys.toMutableSet()
      setAllowAnyDestination(true)
      this.callbackUrlSep31 = "https://example.com/sep31"
    }

  private fun nonCustodialRow(name: String, domains: Set<String>) =
    JdbcClientConfig().apply {
      this.name = name
      this.type = ClientType.NONCUSTODIAL
      this.domains = domains.toMutableSet()
      this.callbackUrlSep24 = "https://example.com/sep24"
    }

  @Test
  fun `getClientConfigByDomain queries the repo and maps to a NonCustodialClient`() {
    every { repo.findByDomain("vibrantapp.com") } returns
      nonCustodialRow("VIBRANT", setOf("vibrantapp.com"))

    val result = service.getClientConfigByDomain("vibrantapp.com")

    assertTrue(result is NonCustodialClient)
    assertEquals("VIBRANT", result!!.name)
    assertEquals(setOf("vibrantapp.com"), result.domains)
    assertEquals("https://example.com/sep24", result.callbackUrls.sep24)
  }

  @Test
  fun `getClientConfigByDomain returns null when the repo finds nothing`() {
    every { repo.findByDomain("unknown.com") } returns null

    assertNull(service.getClientConfigByDomain("unknown.com"))
  }

  @Test
  fun `getClientConfigBySigningKey queries the repo and maps to a CustodialClient`() {
    every { repo.findBySigningKey("GALICE") } returns custodialRow("MGI", setOf("GALICE"))

    val result = service.getClientConfigBySigningKey("GALICE")

    assertTrue(result is CustodialClient)
    assertEquals("MGI", result!!.name)
    assertEquals(setOf("GALICE"), result.signingKeys)
    assertTrue(result.isAllowAnyDestination)
  }

  @Test
  fun `getClientConfigByDomainAndAccount prefers the domain match over the account match`() {
    every { repo.findByDomain("vibrantapp.com") } returns
      nonCustodialRow("VIBRANT", setOf("vibrantapp.com"))
    every { repo.findBySigningKey("GALICE") } returns custodialRow("MGI", setOf("GALICE"))

    val result = service.getClientConfigByDomainAndAccount("vibrantapp.com", "GALICE")

    assertEquals("VIBRANT", result!!.name)
  }

  @Test
  fun `getClientConfigByDomainAndAccount falls back to the account match`() {
    every { repo.findByDomain("unknown.com") } returns null
    every { repo.findBySigningKey("GALICE") } returns custodialRow("MGI", setOf("GALICE"))

    val result = service.getClientConfigByDomainAndAccount("unknown.com", "GALICE")

    assertEquals("MGI", result!!.name)
  }

  @Test
  fun `getClientConfigByName queries by primary key and maps by type`() {
    every { repo.findById("MGI") } returns Optional.of(custodialRow("MGI", setOf("GALICE")))
    every { repo.findById("VIBRANT") } returns
      Optional.of(nonCustodialRow("VIBRANT", setOf("vibrantapp.com")))
    every { repo.findById("UNKNOWN") } returns Optional.empty()

    assertTrue(service.getClientConfigByName("MGI") is CustodialClient)
    assertTrue(service.getClientConfigByName("VIBRANT") is NonCustodialClient)
    assertNull(service.getClientConfigByName("UNKNOWN"))
  }

  @Test
  fun `getCustodialClients and getNonCustodialClients only return their own type`() {
    every { repo.findByType(ClientType.CUSTODIAL) } returns
      listOf(custodialRow("MGI", setOf("GALICE")))
    every { repo.findByType(ClientType.NONCUSTODIAL) } returns
      listOf(nonCustodialRow("VIBRANT", setOf("vibrantapp.com")))

    assertEquals(listOf("MGI"), service.custodialClients.map { it.name })
    assertEquals(listOf("VIBRANT"), service.nonCustodialClients.map { it.name })
  }

  @Test
  fun `getAllClients maps every row regardless of type`() {
    every { repo.findAll() } returns
      listOf(
        custodialRow("MGI", setOf("GALICE")),
        nonCustodialRow("VIBRANT", setOf("vibrantapp.com")),
      )

    val names = service.allClients.map { it.name }.toSet()

    assertEquals(setOf("MGI", "VIBRANT"), names)
  }

  @Test
  fun `getClientConfigBySigningKey returns null for a noncustodial row that carries the key`() {
    every { repo.findBySigningKey("GWALLET") } returns
      nonCustodialRow("VIBRANT", setOf("vibrantapp.com")).apply {
        signingKeys = mutableSetOf("GWALLET")
        setAllowAnyDestination(true)
        destinationAccounts = mutableSetOf("GDEST")
      }

    assertNull(service.getClientConfigBySigningKey("GWALLET"))
  }

  @Test
  fun `getClientConfigBySigningKey returns null when no row holds the key`() {
    every { repo.findBySigningKey("GUNKNOWN") } returns null

    assertNull(service.getClientConfigBySigningKey("GUNKNOWN"))
  }

  @Test
  fun `getClientConfigBySigningKey maps every field of a custodial row`() {
    every { repo.findBySigningKey("GALICE") } returns
      custodialRow("MGI", setOf("GALICE", "GBOB")).apply {
        destinationAccounts = mutableSetOf("GDEST")
        callbackUrlSep6 = "https://example.com/sep6"
        callbackUrlSep24 = "https://example.com/sep24"
        callbackUrlSep12 = "https://example.com/sep12"
      }

    val result = service.getClientConfigBySigningKey("GALICE")!!

    assertEquals("MGI", result.name)
    assertEquals(setOf("GALICE", "GBOB"), result.signingKeys)
    assertEquals(setOf("GDEST"), result.destinationAccounts)
    assertTrue(result.isAllowAnyDestination)
    assertEquals("https://example.com/sep6", result.callbackUrls.sep6)
    assertEquals("https://example.com/sep24", result.callbackUrls.sep24)
    assertEquals("https://example.com/sep31", result.callbackUrls.sep31)
    assertEquals("https://example.com/sep12", result.callbackUrls.sep12)
  }

  @Test
  fun `getClientConfigBySigningKey answers only for keys of getCustodialClients`() {
    val custodial = custodialRow("MGI", setOf("GALICE", "GBOB"))
    val noncustodialWithKey =
      nonCustodialRow("VIBRANT", setOf("vibrantapp.com")).apply {
        signingKeys = mutableSetOf("GWALLET")
      }
    every { repo.findByType(ClientType.CUSTODIAL) } returns listOf(custodial)
    every { repo.findBySigningKey("GALICE") } returns custodial
    every { repo.findBySigningKey("GBOB") } returns custodial
    every { repo.findBySigningKey("GWALLET") } returns noncustodialWithKey
    every { repo.findBySigningKey("GUNKNOWN") } returns null

    val custodialKeys = service.custodialClients.flatMap { it.signingKeys }.toSet()
    val resolvedKeys =
      listOf("GALICE", "GBOB", "GWALLET", "GUNKNOWN")
        .filter { service.getClientConfigBySigningKey(it) != null }
        .toSet()

    assertEquals(setOf("GALICE", "GBOB"), custodialKeys)
    assertEquals(custodialKeys, resolvedKeys)
  }

  @Test
  fun `getClientConfigByDomain returns null for a custodial row that carries the domain`() {
    every { repo.findByDomain("custodial.example") } returns
      custodialRow("MGI", setOf("GALICE")).apply { domains = mutableSetOf("custodial.example") }

    assertNull(service.getClientConfigByDomain("custodial.example"))
  }

  @Test
  fun `a custodial row carrying a domain resolves by its signing key only`() {
    val row = custodialRow("MGI", setOf("GALICE")).apply { domains = mutableSetOf("mgi.example") }
    every { repo.findBySigningKey("GALICE") } returns row
    every { repo.findByDomain("mgi.example") } returns row

    assertEquals("MGI", service.getClientConfigBySigningKey("GALICE")!!.name)
    assertNull(service.getClientConfigByDomain("mgi.example"))
  }

  @Test
  fun `a noncustodial row carrying a signing key resolves by its domain only`() {
    val row =
      nonCustodialRow("VIBRANT", setOf("vibrantapp.com")).apply {
        signingKeys = mutableSetOf("GWALLET")
      }
    every { repo.findBySigningKey("GWALLET") } returns row
    every { repo.findByDomain("vibrantapp.com") } returns row

    assertEquals("VIBRANT", service.getClientConfigByDomain("vibrantapp.com")!!.name)
    assertNull(service.getClientConfigBySigningKey("GWALLET"))
  }

  @Test
  fun `getClientConfigByDomainAndAccount returns null for a noncustodial key and an unknown domain`() {
    every { repo.findByDomain("unknown.com") } returns null
    every { repo.findBySigningKey("GWALLET") } returns
      nonCustodialRow("VIBRANT", setOf("vibrantapp.com")).apply {
        signingKeys = mutableSetOf("GWALLET")
      }

    assertNull(service.getClientConfigByDomainAndAccount("unknown.com", "GWALLET"))
  }
}
