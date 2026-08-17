package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors
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
  fun `type db with a value set still parses to an empty item list`() {
    config.type = ClientsConfig.ClientsConfigType.DB
    config.value = "items:\n  - name: client1\n    type: custodial"

    config.validate(config, errors)

    assertTrue(config.items.isEmpty())
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `migrateFromFileOnStartup defaults to false`() {
    assertFalse(config.isMigrateFromFileOnStartup)
  }

  @Test
  fun `switching from yaml to db clears items parsed from the prior type`() {
    config.type = ClientsConfig.ClientsConfigType.YAML
    config.value = "items:\n  - name: client1\n    type: custodial"
    config.validate(config, errors)
    assertEquals(1, config.items.size)

    config.type = ClientsConfig.ClientsConfigType.DB
    config.validate(config, errors)

    assertTrue(config.items.isEmpty())
  }
}
