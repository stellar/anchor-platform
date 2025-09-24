package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.TestHelper
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.config.ClientsConfig
import org.stellar.anchor.config.ClientsConfig.ClientConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.sep6.ExchangeAmountsCalculatorTest

class ClientFinderTest {
  companion object {
    val token = TestHelper.createSep10Jwt(TEST_ACCOUNT, TEST_MEMO)
    val clientConfig =
      ClientConfig.builder()
        .name("name")
        .type(ClientsConfig.ClientType.CUSTODIAL)
        .signingKeys(setOf("signing-key"))
        .domains(setOf("domain"))
        .callbackUrl("http://localhost:8000")
        .build()
  }

  @MockK(relaxed = true) lateinit var sep10Config: Sep10Config
  @MockK(relaxed = true) lateinit var clientsConfig: ClientsConfig

  private lateinit var clientFinder: ClientFinder

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { sep10Config.isClientAttributionRequired } returns true
    every { sep10Config.allowedClientDomains } returns clientConfig.domains.toList()
    every { sep10Config.allowedClientNames } returns listOf(clientConfig.name)
    every {
      clientsConfig.getClientConfigByDomain(ExchangeAmountsCalculatorTest.token.clientDomain)
    } returns clientConfig
    every {
      clientsConfig.getClientConfigBySigningKey(ExchangeAmountsCalculatorTest.token.account)
    } returns clientConfig

    clientFinder = ClientFinder(sep10Config, clientsConfig)
  }

  @Test
  fun `test getClientName with client found by domain`() {
    every { clientsConfig.getClientConfigByDomain(token.clientDomain) } returns clientConfig
    val clientId = clientFinder.getClientName(token)

    assertEquals(clientConfig.name, clientId)
  }

  @Test
  fun `test getClientName with client found by signing key`() {
    every { clientsConfig.getClientConfigByDomain(token.clientDomain) } returns null
    val clientId = clientFinder.getClientName(token)

    assertEquals(clientConfig.name, clientId)
  }

  @Test
  fun `test getClientName with client not found`() {
    every { clientsConfig.getClientConfigByDomain(token.clientDomain) } returns null
    every { clientsConfig.getClientConfigBySigningKey(token.account) } returns null

    assertThrows<SepNotAuthorizedException> { clientFinder.getClientName(token) }
  }

  @Test
  fun `test getClientName with client not found by domain`() {
    every { sep10Config.allowedClientNames } returns listOf("nothing")

    assertThrows<SepNotAuthorizedException> { clientFinder.getClientName(token) }
  }

  @Test
  fun `test getClientName with client not found by name`() {
    every { sep10Config.allowedClientNames } returns listOf("nothing")

    assertThrows<SepNotAuthorizedException> { clientFinder.getClientName(token) }
  }

  @Test
  fun `test getClientName with all names allowed`() {
    every { sep10Config.allowedClientNames } returns listOf(clientConfig.name)
    val clientId = clientFinder.getClientName(token)

    assertEquals(clientConfig.name, clientId)
  }

  @Test
  fun `test getClientName with client attribution disabled and missing client`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { clientsConfig.getClientConfigByDomain(token.clientDomain) } returns null
    every { clientsConfig.getClientConfigBySigningKey(token.account) } returns null

    val clientId = clientFinder.getClientName(token)
    assertNull(clientId)
  }

  @Test
  fun `test getClientName with client attribution disabled and client found by signing key`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { clientsConfig.getClientConfigByDomain(token.clientDomain) } returns null
    every { clientsConfig.getClientConfigBySigningKey(token.account) } returns clientConfig

    val clientId = clientFinder.getClientName(token)
    assertEquals(clientConfig.name, clientId)
  }

  @Test
  fun `test getClientName with client attribution disabled and client found by domain`() {
    every { sep10Config.isClientAttributionRequired } returns false
    every { clientsConfig.getClientConfigByDomain(token.clientDomain) } returns clientConfig
    every { clientsConfig.getClientConfigBySigningKey(token.account) } returns null

    val clientId = clientFinder.getClientName(token)
    assertEquals(clientConfig.name, clientId)
  }
}
