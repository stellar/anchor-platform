package org.stellar.anchor.platform.config

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.client.ClientConfig.ClientType
import org.stellar.anchor.config.ClientsConfig

class PropertyClientsConfigTest {
  private lateinit var config: PropertyClientsConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setup() {
    config = PropertyClientsConfig()
    errors = BindException(config, "config")
  }

  @Test
  fun `type db with no value parses to an empty item list`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.value = null

    config.validate(config, errors)

    assertTrue(config.items.isEmpty())
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `type db with a YAML string left in value migrates its items`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.value = "items:\n  - name: client1\n    type: custodial\n    signing_keys:\n      - GABC"

    config.validate(config, errors)

    assertEquals(1, config.items.size)
    assertEquals("client1", config.items[0].name)
  }

  @Test
  fun `type db with a JSON string left in value migrates its items`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.value = """{"items":[{"name":"client1","type":"custodial","signing_keys":["GABC"]}]}"""

    config.validate(config, errors)

    assertEquals(1, config.items.size)
    assertEquals("client1", config.items[0].name)
  }

  @Test
  fun `type db with a file path left in value migrates its items`() {
    val file = Files.createTempFile("clients", ".yaml")
    try {
      Files.writeString(
        file,
        "items:\n  - name: client1\n    type: custodial\n    signing_keys:\n      - GABC",
      )
      config.type = ClientsConfig.ClientsConfigType.DB
      config.value = file.toString()

      config.validate(config, errors)

      assertEquals(1, config.items.size)
      assertEquals("client1", config.items[0].name)
    } finally {
      Files.deleteIfExists(file)
    }
  }

  @Test
  fun `type db with an unparseable value fails validation instead of silently importing nothing`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.value = "/typo/path/that/does/not/exist.yaml"

    config.validate(config, errors)

    assertTrue(errors.hasErrors())
    assertTrue(config.items.isEmpty())
  }

  @Test
  fun `type db with a parseable value missing the items key fails validation`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.value = "not_items: []"

    config.validate(config, errors)

    assertTrue(errors.hasErrors())
    assertTrue(config.items.isEmpty())
  }

  @Test
  fun `type db preserves items already bound directly, e_g_ left over from an inline config`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.items =
      listOf(
        ClientsConfig.RawClient.builder()
          .name("client1")
          .type(ClientType.CUSTODIAL)
          .signingKeys(setOf("GABC"))
          .build()
      )

    config.validate(config, errors)

    assertEquals(1, config.items.size)
    assertEquals("client1", config.items[0].name)
  }
}
