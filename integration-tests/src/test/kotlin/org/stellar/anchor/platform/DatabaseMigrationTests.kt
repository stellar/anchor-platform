package org.stellar.anchor.platform

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

val PostgresConfig =
  TestConfig("default").also {
    it.env[RUN_DOCKER] = "false"
    it.env[RUN_ALL_SERVERS] = "false"
    it.env[RUN_SEP_SERVER] = "true"
    it.env["data.flyway_enabled"] = "true"
  }

val H2Config =
  TestConfig("default").also {
    it.env[RUN_DOCKER] = "false"
    it.env[RUN_ALL_SERVERS] = "false"
    it.env[RUN_SEP_SERVER] = "true"
    it.env["data.type"] = "h2"
    it.env["data.server"] = ""
    it.env["data.database"] = ""
    it.env["data.flyway_enabled"] = "true"
  }

val SQLiteConfig =
  TestConfig("default").also {
    it.env[RUN_DOCKER] = "false"
    it.env[RUN_ALL_SERVERS] = "false"
    it.env[RUN_SEP_SERVER] = "true"
    it.env["data.type"] = "sqlite"
    it.env["data.server"] = ""
    it.env["data.database"] = "platform-test"
    it.env["data.flyway_enabled"] = "true"
  }

class PostgresMigrationTest : AbstractIntegrationTest(PostgresConfig) {
  companion object {
    private val singleton = PostgresMigrationTest()

    @BeforeAll
    @JvmStatic
    fun construct() {
      println("Running PostgresMigrationTest")
      singleton.testProfileRunner.start()
    }

    @AfterAll
    @JvmStatic
    fun destroy() {
      singleton.testProfileRunner.shutdown()
    }
  }

  @Test
  fun test() {
    // Nothing to do
  }
}

class H2MigrationTest : AbstractIntegrationTest(H2Config) {
  companion object {
    private val singleton = H2MigrationTest()

    @BeforeAll
    @JvmStatic
    fun construct() {
      println("Running H2MigrationTest")
      singleton.testProfileRunner.start()
    }

    @AfterAll
    @JvmStatic
    fun destroy() {
      singleton.testProfileRunner.start()
    }
  }

  @Test
  fun test() {
    // Nothing to do
  }
}

class SQLiteMigrationTest : AbstractIntegrationTest(SQLiteConfig) {
  companion object {
    private val singleton = SQLiteMigrationTest()

    @BeforeAll
    @JvmStatic
    fun construct() {
      println("Running SQLiteMigrationTest")
      singleton.testProfileRunner.start()
    }

    @AfterAll
    @JvmStatic
    fun destroy() {
      singleton.testProfileRunner.shutdown()
    }
  }

  @Test
  fun test() {
    // Nothing to do
  }
}
