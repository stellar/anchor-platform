package org.stellar.anchor.platform.data

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.stellar.anchor.platform.data.SepIndexSupport.IndexInfo
import org.stellar.anchor.platform.data.SepIndexSupport.indexColumns
import org.stellar.anchor.platform.data.SepIndexSupport.indexName
import org.stellar.anchor.platform.data.SepIndexSupport.tables
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * HackerOne #4240810, the deployments that do not run Flyway: with `data.flyway_enabled` false (the
 * default) the platform sets `spring.jpa.hibernate.ddl-auto=update` and Hibernate creates the
 * schema from the entities, so the indexes of `V38__sep24_sep6_lookup_indexes.sql` must also be
 * declared on `JdbcSep24Transaction` and `JdbcSep6Transaction`, under the same names. Each scenario
 * runs Hibernate `update` against its own empty PostgreSQL schema. Needs Docker.
 */
@Testcontainers
class SepTransactionLookupIndexesHibernateTest {

  companion object {
    @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:15.2-alpine")
  }

  private val contexts = mutableListOf<ConfigurableApplicationContext>()

  @AfterEach
  fun closeContexts() {
    contexts.forEach { it.close() }
    contexts.clear()
  }

  private fun newSchema(name: String): JdbcTemplate {
    val admin =
      JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    admin.execute("CREATE SCHEMA $name")
    return SepIndexSupport.jdbc(postgres.jdbcUrl, postgres.username, postgres.password, name)
  }

  /** Starts the JPA context, which runs Hibernate `update`, and closes it. */
  private fun runHibernateUpdate(schema: String) {
    val context =
      SepIndexSupport.context(
        DriverManagerDataSource(
          SepIndexSupport.urlFor(postgres.jdbcUrl, schema),
          postgres.username,
          postgres.password,
        ),
        "update",
      )
    contexts += context
    context.close()
    contexts.clear()
  }

  private fun assertAllIndexes(schema: String) {
    val jdbc = SepIndexSupport.jdbc(postgres.jdbcUrl, postgres.username, postgres.password, schema)
    tables.forEach { table ->
      val catalog = SepIndexSupport.catalog(jdbc, schema, table)
      indexColumns.forEach { (suffix, columns) ->
        assertEquals(
          IndexInfo(valid = true, method = "btree", columns = columns),
          catalog[indexName(table, suffix)],
          "$suffix index of $table",
        )
      }
    }
  }

  private fun indexCounts(jdbc: JdbcTemplate, schema: String) =
    tables.associateWith { SepIndexSupport.catalog(jdbc, schema, it).keys.sorted() }

  // HIB-01 AC1
  @Test
  fun `Hibernate update creates the four indexes of each table on an empty schema`() {
    newSchema("hib_fresh")

    runHibernateUpdate("hib_fresh")

    assertAllIndexes("hib_fresh")
  }

  // HIB-01 AC2
  @Test
  fun `Hibernate update creates the indexes on tables that exist without them`() {
    val jdbc = newSchema("hib_upgrade")
    runHibernateUpdate("hib_upgrade")
    // The tables as an earlier version created them: no lookup index.
    tables.forEach { table ->
      indexColumns.keys.forEach { jdbc.execute("DROP INDEX IF EXISTS ${indexName(table, it)}") }
    }
    tables.forEach {
      val lookupIndexes =
        SepIndexSupport.catalog(jdbc, "hib_upgrade", it).keys.filter { n -> n.startsWith("idx_") }
      assertEquals(emptyList<String>(), lookupIndexes, "setup: $it must have no lookup index")
    }

    runHibernateUpdate("hib_upgrade")

    assertAllIndexes("hib_upgrade")
  }

  // HIB-01 AC3
  @Test
  fun `a second Hibernate update creates no additional index`() {
    val jdbc = newSchema("hib_twice")
    runHibernateUpdate("hib_twice")
    val afterFirst = indexCounts(jdbc, "hib_twice")

    runHibernateUpdate("hib_twice")

    assertEquals(afterFirst, indexCounts(jdbc, "hib_twice"))
  }
}
