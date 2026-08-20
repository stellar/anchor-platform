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
}
