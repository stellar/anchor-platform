package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
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
  private lateinit var repoProvider: ObjectProvider<JdbcClientConfigRepo>

  private lateinit var service: ClientConfigService

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    repoProvider = mockk { every { getObject() } returns repo }
    service = ClientConfigService(repoProvider)
  }

  private fun custodialRequest(
    signingKeys: Set<String> = setOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
  ) =
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
    assertEquals(
      setOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL"),
      response.signingKeys
    )
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

    service.upsert(
      "MGI",
      custodialRequest(setOf("GD3TURJDV37E7S7XTI63CJJXEWEVNB3MBSCSGM5LQAWKQ7O5SPAW7ZXM"))
    )

    assertSame(existing, saved.captured)
    assertEquals(
      setOf("GD3TURJDV37E7S7XTI63CJJXEWEVNB3MBSCSGM5LQAWKQ7O5SPAW7ZXM"),
      saved.captured.signingKeys
    )
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
    val request =
      ClientConfigRequest().apply {
        signingKeys = setOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
      }

    assertThrows(BadRequestException::class.java) { service.upsert("MGI", request) }
  }

  @Test
  fun `upsert rejects a blank client name`() {
    assertThrows(BadRequestException::class.java) { service.upsert(" ", custodialRequest()) }
  }

  @Test
  fun `upsert translates a known unique constraint violation into a clean BadRequestException`() {
    every { repo.findById("MGI") } returns Optional.empty()
    every { repo.save(any()) } throws
      DataIntegrityViolationException(
        "duplicate key",
        RuntimeException(
          "ERROR: duplicate key value violates unique constraint \"idx_client_signing_key_key\""
        ),
      )

    val ex =
      assertThrows(BadRequestException::class.java) { service.upsert("MGI", custodialRequest()) }
    assertEquals("domain or signing key is already in use by another client", ex.message)
  }

  @Test
  fun `upsert rethrows a DataIntegrityViolationException that isn't a known unique constraint`() {
    every { repo.findById("MGI") } returns Optional.empty()
    val unrelated =
      DataIntegrityViolationException("not-null constraint violated on some other column")
    every { repo.save(any()) } throws unrelated

    val thrown =
      assertThrows(DataIntegrityViolationException::class.java) {
        service.upsert("MGI", custodialRequest())
      }
    assertSame(unrelated, thrown)
  }

  @Test
  fun `upsert clears previously stored callback URLs when the request omits them`() {
    val existing =
      JdbcClientConfig().apply {
        name = "MGI"
        type = ClientType.CUSTODIAL
        callbackUrlSep24 = "https://old.example.com/sep24"
      }
    every { repo.findById("MGI") } returns Optional.of(existing)
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    service.upsert("MGI", custodialRequest())

    assertNull(saved.captured.callbackUrlSep24)
  }

  @Test
  fun `upsert rejects a callback URL with a non-http scheme`() {
    every { repo.findById("VIBRANT") } returns Optional.empty()
    val request =
      nonCustodialRequest().apply {
        callbackUrls = CallbackUrls.builder().sep24("file:///etc/passwd").build()
      }

    assertThrows(BadRequestException::class.java) { service.upsert("VIBRANT", request) }
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
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
      }
    every { repo.findById("MGI") } returns Optional.of(entity)

    val response = service.get("MGI")

    assertEquals("MGI", response.name)
    assertEquals(
      setOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL"),
      response.signingKeys
    )
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
  fun `listCustodial returns only custodial clients`() {
    every { repo.findByType(ClientType.CUSTODIAL) } returns
      listOf(
        JdbcClientConfig().apply {
          name = "MGI"
          type = ClientType.CUSTODIAL
        }
      )

    val result = service.listCustodial()

    assertEquals(1, result.size)
    assertEquals("MGI", result[0].name)
    assertEquals(ClientType.CUSTODIAL, result[0].type)
    verify(exactly = 0) { repo.findByType(ClientType.NONCUSTODIAL) }
  }

  @Test
  fun `listNonCustodial returns only noncustodial clients`() {
    every { repo.findByType(ClientType.NONCUSTODIAL) } returns
      listOf(
        JdbcClientConfig().apply {
          name = "VIBRANT"
          type = ClientType.NONCUSTODIAL
        }
      )

    val result = service.listNonCustodial()

    assertEquals(1, result.size)
    assertEquals("VIBRANT", result[0].name)
    assertEquals(ClientType.NONCUSTODIAL, result[0].type)
    verify(exactly = 0) { repo.findByType(ClientType.CUSTODIAL) }
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

  @Test
  fun `addSigningKey adds a key without disturbing the existing ones`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys =
          mutableSetOf(
            "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL",
            "GBJFPH252QQPPK3VSDLTPITYQG2I66CT7BCE3U7VUWXZQJNQZZ74G5K2"
          )
      }
    every { repo.findById("DING") } returns Optional.of(existing)
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    val response =
      service.addSigningKey("DING", "GDF4WLQVPVTMLZOG7T677ULCBFUY3HY723IBBUVPO6RPQRKHJC6ATGA7")

    assertEquals(
      setOf(
        "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL",
        "GBJFPH252QQPPK3VSDLTPITYQG2I66CT7BCE3U7VUWXZQJNQZZ74G5K2",
        "GDF4WLQVPVTMLZOG7T677ULCBFUY3HY723IBBUVPO6RPQRKHJC6ATGA7"
      ),
      saved.captured.signingKeys
    )
    assertEquals(
      setOf(
        "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL",
        "GBJFPH252QQPPK3VSDLTPITYQG2I66CT7BCE3U7VUWXZQJNQZZ74G5K2",
        "GDF4WLQVPVTMLZOG7T677ULCBFUY3HY723IBBUVPO6RPQRKHJC6ATGA7"
      ),
      response.signingKeys
    )
  }

  @Test
  fun `addSigningKey throws NotFoundException when the client does not exist`() {
    every { repo.findById("UNKNOWN") } returns Optional.empty()

    assertThrows(NotFoundException::class.java) {
      service.addSigningKey("UNKNOWN", "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
    }
  }

  @Test
  fun `addSigningKey rejects a malformed signing key before touching the repo`() {
    val ex =
      assertThrows(BadRequestException::class.java) {
        service.addSigningKey("DING", "not-a-valid-signing-key")
      }
    assertEquals("Invalid signing key: not-a-valid-signing-key", ex.message)
    verify(exactly = 0) { repo.findById(any()) }
    verify(exactly = 0) { repo.save(any()) }
  }

  @Test
  fun `upsert rejects a custodial client whose signing key is not a valid strkey`() {
    every { repo.findById("MGI") } returns Optional.empty()

    val ex =
      assertThrows(BadRequestException::class.java) {
        service.upsert("MGI", custodialRequest(setOf("not-a-valid-signing-key")))
      }
    assertEquals("Invalid signing key: not-a-valid-signing-key", ex.message)
    verify(exactly = 0) { repo.save(any()) }
  }

  @Test
  fun `addSigningKey translates a unique constraint violation into a clean BadRequestException`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
      }
    every { repo.findById("DING") } returns Optional.of(existing)
    every { repo.save(any()) } throws
      DataIntegrityViolationException(
        "duplicate key",
        RuntimeException(
          "ERROR: duplicate key value violates unique constraint \"idx_client_signing_key_key\""
        ),
      )

    val ex =
      assertThrows(BadRequestException::class.java) {
        service.addSigningKey("DING", "GCH7Q6BPM6AABOL7B3X3VRYG26NDZEOHTH4YZJV5JMS2TO2I6EF5G63Y")
      }
    assertEquals("domain or signing key is already in use by another client", ex.message)
  }

  @Test
  fun `removeSigningKey removes only the requested key`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys =
          mutableSetOf(
            "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL",
            "GBJFPH252QQPPK3VSDLTPITYQG2I66CT7BCE3U7VUWXZQJNQZZ74G5K2"
          )
      }
    every { repo.findById("DING") } returns Optional.of(existing)
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    service.removeSigningKey("DING", "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")

    assertEquals(
      setOf("GBJFPH252QQPPK3VSDLTPITYQG2I66CT7BCE3U7VUWXZQJNQZZ74G5K2"),
      saved.captured.signingKeys
    )
  }

  @Test
  fun `removeSigningKey throws NotFoundException when the client does not exist`() {
    every { repo.findById("UNKNOWN") } returns Optional.empty()

    assertThrows(NotFoundException::class.java) {
      service.removeSigningKey(
        "UNKNOWN",
        "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL"
      )
    }
  }

  @Test
  fun `removeSigningKey throws NotFoundException when the key isn't on the client`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
      }
    every { repo.findById("DING") } returns Optional.of(existing)

    assertThrows(NotFoundException::class.java) {
      service.removeSigningKey("DING", "GC7RK5Y7YB3COC2ONJSDLOMCPVNOFTVIHRVCJ4EMGTIVXRPGFRXCEP4Y")
    }
  }

  @Test
  fun `removeSigningKey rejects removing the last signing key of a custodial client`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
      }
    every { repo.findById("DING") } returns Optional.of(existing)

    assertThrows(BadRequestException::class.java) {
      service.removeSigningKey("DING", "GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
    }
    verify(exactly = 0) { repo.save(any()) }
  }

  @Test
  fun `addDestinationAccount adds an account without disturbing the existing ones`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
        destinationAccounts =
          mutableSetOf("GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C")
      }
    every { repo.findById("DING") } returns Optional.of(existing)
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    val response =
      service.addDestinationAccount(
        "DING",
        "GBEZ7LGFD5VJKWA4ZEO7CJF7LGP6RZCPU266SMKDSNHYGZGW3TLNERVE"
      )

    assertEquals(
      setOf(
        "GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C",
        "GBEZ7LGFD5VJKWA4ZEO7CJF7LGP6RZCPU266SMKDSNHYGZGW3TLNERVE"
      ),
      saved.captured.destinationAccounts
    )
    assertEquals(
      setOf(
        "GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C",
        "GBEZ7LGFD5VJKWA4ZEO7CJF7LGP6RZCPU266SMKDSNHYGZGW3TLNERVE"
      ),
      response.destinationAccounts
    )
  }

  @Test
  fun `addDestinationAccount throws NotFoundException when the client does not exist`() {
    every { repo.findById("UNKNOWN") } returns Optional.empty()

    assertThrows(NotFoundException::class.java) {
      service.addDestinationAccount(
        "UNKNOWN",
        "GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C"
      )
    }
  }

  @Test
  fun `addDestinationAccount rejects a malformed account before touching the repo`() {
    val ex =
      assertThrows(BadRequestException::class.java) {
        service.addDestinationAccount("DING", "not-a-valid-account")
      }
    assertEquals("Invalid destination account: not-a-valid-account", ex.message)
    verify(exactly = 0) { repo.findById(any()) }
    verify(exactly = 0) { repo.save(any()) }
  }

  @Test
  fun `upsert rejects a custodial client whose destination account is not a valid strkey`() {
    every { repo.findById("MGI") } returns Optional.empty()
    val request = custodialRequest().apply { destinationAccounts = setOf("not-a-valid-account") }

    val ex = assertThrows(BadRequestException::class.java) { service.upsert("MGI", request) }
    assertEquals("Invalid destination account: not-a-valid-account", ex.message)
    verify(exactly = 0) { repo.save(any()) }
  }

  @Test
  fun `removeDestinationAccount removes only the requested account`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
        destinationAccounts =
          mutableSetOf(
            "GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C",
            "GBEZ7LGFD5VJKWA4ZEO7CJF7LGP6RZCPU266SMKDSNHYGZGW3TLNERVE"
          )
      }
    every { repo.findById("DING") } returns Optional.of(existing)
    val saved = slot<JdbcClientConfig>()
    every { repo.save(capture(saved)) } answers { saved.captured }

    service.removeDestinationAccount(
      "DING",
      "GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C"
    )

    assertEquals(
      setOf("GBEZ7LGFD5VJKWA4ZEO7CJF7LGP6RZCPU266SMKDSNHYGZGW3TLNERVE"),
      saved.captured.destinationAccounts
    )
  }

  @Test
  fun `removeDestinationAccount throws NotFoundException when the account isn't on the client`() {
    val existing =
      JdbcClientConfig().apply {
        name = "DING"
        type = ClientType.CUSTODIAL
        signingKeys = mutableSetOf("GDT5Z3R4YY6ROC5AJHNCY5KKMPVFLXG5RU3PDHCVFS2Z4DKAMO3GBSIL")
        destinationAccounts =
          mutableSetOf("GBWO3GPFE2QAQS45RB3M4ARB73DHWGPHTCKQPMJOTWWEBPWZTGQ4OM4C")
      }
    every { repo.findById("DING") } returns Optional.of(existing)

    assertThrows(NotFoundException::class.java) {
      service.removeDestinationAccount(
        "DING",
        "GC7RK5Y7YB3COC2ONJSDLOMCPVNOFTVIHRVCJ4EMGTIVXRPGFRXCEP4Y"
      )
    }
  }
}
