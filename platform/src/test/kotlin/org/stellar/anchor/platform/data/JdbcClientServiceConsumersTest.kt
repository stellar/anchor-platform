package org.stellar.anchor.platform.data

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.client.ClientConfig.ClientType
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.util.SepRequestValidator
import org.stellar.anchor.util.SepRequestValidator.ERR_TOKEN_ACCOUNT_MISMATCH
import org.stellar.sdk.KeyPair

/**
 * The reporter's row of HackerOne #4108778: a noncustodial client that also carries a signing key,
 * a destination list and allow_any_destination, resolved through the real finder and gate.
 */
class JdbcClientServiceConsumersTest {

  @MockK private lateinit var repo: JdbcClientConfigRepo
  @MockK(relaxed = true) private lateinit var sep10Config: Sep10Config
  @MockK private lateinit var assetService: AssetService

  private lateinit var service: JdbcClientService
  private lateinit var clientFinder: ClientFinder
  private lateinit var validator: SepRequestValidator

  private val walletKey = KeyPair.random().accountId
  private val destination = KeyPair.random().accountId

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this)
    service = JdbcClientService(repo)
    clientFinder = ClientFinder(sep10Config, service)
    validator = SepRequestValidator(assetService, service)
    every { sep10Config.allowedClientNames } returns listOf("VIBRANT", "MGI")
    every { repo.findByDomain(any()) } returns null
  }

  private fun noncustodialRowWithKey() =
    JdbcClientConfig().apply {
      name = "VIBRANT"
      type = ClientType.NONCUSTODIAL
      domains = mutableSetOf("vibrantapp.com")
      signingKeys = mutableSetOf(walletKey)
      destinationAccounts = mutableSetOf(destination)
      setAllowAnyDestination(true)
    }

  private fun custodialRow() =
    JdbcClientConfig().apply {
      name = "MGI"
      type = ClientType.CUSTODIAL
      signingKeys = mutableSetOf(walletKey)
      setAllowAnyDestination(true)
    }

  @Test
  fun `client finder returns no client name for a noncustodial key when attribution is optional`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { repo.findBySigningKey(walletKey) } returns noncustodialRowWithKey()

    assertNull(clientFinder.getClientName(null, walletKey))
  }

  @Test
  fun `client finder refuses a noncustodial key when attribution is required`() {
    every { sep10Config.isClientAttributionRequired } returns true
    every { repo.findBySigningKey(walletKey) } returns noncustodialRowWithKey()

    val ex = assertThrows<SepNotAuthorizedException> { clientFinder.getClientName(null, walletKey) }

    assertEquals("Client not found", ex.message)
  }

  @Test
  fun `destination gate stays closed for a noncustodial row with allowAnyDestination`() {
    every { repo.findBySigningKey(walletKey) } returns
      noncustodialRowWithKey().apply { destinationAccounts = mutableSetOf() }

    val ex =
      assertThrows<SepValidationException> {
        validator.validateDestinationAccount(walletKey, destination)
      }

    assertEquals(ERR_TOKEN_ACCOUNT_MISMATCH, ex.message)
  }

  @Test
  fun `destination gate stays closed for a destination listed on a noncustodial row`() {
    every { repo.findBySigningKey(walletKey) } returns
      noncustodialRowWithKey().apply { setAllowAnyDestination(false) }

    val ex =
      assertThrows<SepValidationException> {
        validator.validateDestinationAccount(walletKey, destination)
      }

    assertEquals(ERR_TOKEN_ACCOUNT_MISMATCH, ex.message)
  }

  @Test
  fun `destination gate still opens for a custodial row with allowAnyDestination`() {
    every { repo.findBySigningKey(walletKey) } returns custodialRow()

    validator.validateDestinationAccount(walletKey, destination)
  }
}
