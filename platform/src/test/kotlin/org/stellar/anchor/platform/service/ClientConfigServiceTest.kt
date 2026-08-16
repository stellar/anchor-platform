package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.client.ClientConfig.CallbackUrls
import org.stellar.anchor.client.ClientConfig.ClientType
import org.stellar.anchor.platform.controller.platform.ClientConfigRequest
import org.stellar.anchor.platform.data.JdbcClientConfig
import org.stellar.anchor.platform.data.JdbcClientConfigRepo

class ClientConfigServiceTest {

  @MockK(relaxed = true) private lateinit var repo: JdbcClientConfigRepo

  private lateinit var service: ClientConfigService

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    service = ClientConfigService(repo)
  }

  private fun custodialRequest(signingKeys: Set<String> = setOf("GALICE")) =
    ClientConfigRequest().apply {
      type = ClientType.CUSTODIAL
      this.signingKeys = signingKeys
      setAllowAnyDestination(false)
    }

  private fun nonCustodialRequest(domains: Set<String> = setOf("vibrantapp.com")) =
    ClientConfigRequest().apply {
      type = ClientType.NONCUSTODIAL
      this.domains = domains
      callbackUrls = CallbackUrls.builder().sep24("https://vibrantapp.com/callback").build()
    }

  @Test
  fun `upsert creates a new custodial client and saves it`() {
    every { repo.findById("MGI") } returns Optional.empty()
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    val response = service.upsert("MGI", custodialRequest())

    assertEquals("MGI", response.name)
    assertEquals(ClientType.CUSTODIAL, response.type)
    assertEquals(setOf("GALICE"), response.signingKeys)
  }

  @Test
  fun `upsert updates an existing entity rather than creating a duplicate`() {
    val existing =
      JdbcClientConfig().apply {
        name = "MGI"
        type = ClientType.CUSTODIAL
      }
    every { repo.findById("MGI") } returns Optional.of(existing)
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    service.upsert("MGI", custodialRequest(setOf("GNEWKEY")))

    assertSame(existing, saved.captured)
    assertEquals(setOf("GNEWKEY"), saved.captured.signingKeys)
  }

  @Test
  fun `upsert rejects a custodial client with no signing keys`() {
    every { repo.findById("MGI") } returns Optional.empty()

    assertThrows(BadRequestException::class.java) {
      service.upsert("MGI", custodialRequest(emptySet()))
    }
  }

  @Test
  fun `upsert rejects a noncustodial client with no domains`() {
    every { repo.findById("VIBRANT") } returns Optional.empty()

    assertThrows(BadRequestException::class.java) {
      service.upsert("VIBRANT", nonCustodialRequest(emptySet()))
    }
  }

  @Test
  fun `upsert rejects a malformed callback URL`() {
    every { repo.findById("VIBRANT") } returns Optional.empty()
    val request =
      nonCustodialRequest().apply {
        callbackUrls = CallbackUrls.builder().sep24("not-a-url").build()
      }

    assertThrows(BadRequestException::class.java) { service.upsert("VIBRANT", request) }
  }

  @Test
  fun `upsert rejects a missing type`() {
    every { repo.findById("MGI") } returns Optional.empty()
    val request = ClientConfigRequest().apply { signingKeys = setOf("GALICE") }

    assertThrows(BadRequestException::class.java) { service.upsert("MGI", request) }
  }

  @Test
  fun `upsert rejects a blank client name`() {
    assertThrows(BadRequestException::class.java) { service.upsert(" ", custodialRequest()) }
  }

  @Test
  fun `upsert translates a unique constraint violation into a clean BadRequestException`() {
    every { repo.findById("MGI") } returns Optional.empty()
    every { repo.save(any()) } throws DataIntegrityViolationException("duplicate key")

    val ex =
      assertThrows(BadRequestException::class.java) { service.upsert("MGI", custodialRequest()) }
    assertEquals("domain or signing key is already in use by another client", ex.message)
  }

  @Test
  fun `get throws NotFoundException when the client does not exist`() {
    every { repo.findById("UNKNOWN") } returns Optional.empty()

    assertThrows(NotFoundException::class.java) { service.get("UNKNOWN") }
  }

  @Test
  fun `get returns the mapped response when the client exists`() {
    val entity =
      JdbcClientConfig().apply {
        name = "MGI"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GALICE")
      }
    every { repo.findById("MGI") } returns Optional.of(entity)

    val response = service.get("MGI")

    assertEquals("MGI", response.name)
    assertEquals(setOf("GALICE"), response.signingKeys)
  }

  @Test
  fun `list maps every row returned by the repo`() {
    every { repo.findAll() } returns
      listOf(
        JdbcClientConfig().apply {
          name = "MGI"
          type = ClientType.CUSTODIAL
        }
      )

    val result = service.list()

    assertEquals(1, result.size)
    assertEquals("MGI", result[0].name)
  }

  @Test
  fun `delete throws NotFoundException instead of silently no-oping`() {
    every { repo.existsById("UNKNOWN") } returns false

    assertThrows(NotFoundException::class.java) { service.delete("UNKNOWN") }
  }

  @Test
  fun `delete removes an existing client`() {
    every { repo.existsById("MGI") } returns true

    service.delete("MGI")

    verify(exactly = 1) { repo.deleteById("MGI") }
  }
}
