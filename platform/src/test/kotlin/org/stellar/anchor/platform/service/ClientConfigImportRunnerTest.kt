package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.client.ClientConfig.ClientType
import org.stellar.anchor.config.ClientsConfig
import org.stellar.anchor.config.ClientsConfig.RawClient

class ClientConfigImportRunnerTest {

  @MockK(relaxed = true) private lateinit var clientsConfig: ClientsConfig
  @MockK(relaxed = true) private lateinit var clientConfigService: ClientConfigService

  private lateinit var runner: ClientConfigImportRunner

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    runner = ClientConfigImportRunner(clientsConfig, clientConfigService)
  }

  private fun rawCustodial(name: String) =
    RawClient.builder().name(name).type(ClientType.CUSTODIAL).signingKeys(setOf("GALICE")).build()

  @Test
  fun `run upserts every item from clientsConfig`() {
    every { clientsConfig.items } returns listOf(rawCustodial("MGI"), rawCustodial("VIBRANT"))

    runner.run()

    verify(exactly = 1) { clientConfigService.upsert("MGI", any()) }
    verify(exactly = 1) { clientConfigService.upsert("VIBRANT", any()) }
  }

  @Test
  fun `run continues past a single item failure`() {
    every { clientsConfig.items } returns listOf(rawCustodial("BAD"), rawCustodial("GOOD"))
    every { clientConfigService.upsert("BAD", any()) } throws BadRequestException("boom")

    assertDoesNotThrow { runner.run() }

    verify(exactly = 1) { clientConfigService.upsert("BAD", any()) }
    verify(exactly = 1) { clientConfigService.upsert("GOOD", any()) }
  }

  @Test
  fun `run does nothing when clientsConfig has no items`() {
    every { clientsConfig.items } returns emptyList()

    assertDoesNotThrow { runner.run() }

    verify(exactly = 0) { clientConfigService.upsert(any(), any()) }
  }
}
