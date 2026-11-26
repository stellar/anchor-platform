package org.stellar.anchor.platform.data

import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.data.SepIndexSupport.IndexInfo
import org.stellar.anchor.platform.data.SepIndexSupport.PlanNode
import org.stellar.anchor.platform.data.SepIndexSupport.indexName
import org.stellar.anchor.platform.data.SepIndexSupport.tables
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * HackerOne #4240810: a lookup of a SEP-24 or SEP-6 transaction by `transaction_id`,
 * `stellar_transaction_id` or `external_transaction_id`, and the wallet's list by
 * `(web_auth_account, started_at)`, scanned the whole table because none of those columns had an
 * index. These tests run the project's own Flyway migrations on a real PostgreSQL: they seed 50,000
 * rows per table at V37 (the schema before the fix) and record the plans, migrate to the latest
 * version, and check the catalog and the plans again. Needs Docker.
 *
 * The control (the same lookup at V37) is the reporter's "with and without the index" run: it shows
 * the 50,000-row table is large enough for PostgreSQL to prefer a scan when no index exists.
 */
@Testcontainers
class SepTransactionLookupIndexesMigrationTest {

  companion object {
    @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:15.2-alpine")

    private const val SCHEMA = "main"
    private const val ROWS = 50_000
    private val lookupColumns =
      listOf("transaction_id", "stellar_transaction_id", "external_transaction_id")

    private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    private val controlLookupPlans = mutableMapOf<Pair<String, String>, List<PlanNode>>()
    private val controlListPlans = mutableMapOf<String, List<PlanNode>>()
    private lateinit var countsBefore: Map<String, Int>
    private lateinit var schemaBefore: List<String>
    private var migrationError: Throwable? = null

    private fun flyway(schema: String, target: String? = null): Flyway =
      Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .schemas(schema)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    private fun jdbcFor(schema: String) =
      SepIndexSupport.jdbc(postgres.jdbcUrl, postgres.username, postgres.password, schema)

    /** Columns and constraints of the two tables, as text, so two snapshots compare with equals. */
    private fun schemaSnapshot(): List<String> =
      jdbc.queryForList(
        """
        SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable || ' ' ||
               coalesce(character_maximum_length::text, '-')
        FROM information_schema.columns
        WHERE table_schema = '$SCHEMA' AND table_name IN ('sep24_transaction', 'sep6_transaction')
        UNION ALL
        SELECT conrelid::regclass::text || ' ' || conname || ' ' || pg_get_constraintdef(oid)
        FROM pg_constraint
        WHERE connamespace = '$SCHEMA'::regnamespace
          AND conrelid::regclass::text IN ('sep24_transaction', 'sep6_transaction')
        ORDER BY 1
        """,
        String::class.java,
      )

    @BeforeAll
    @JvmStatic
    fun migrateSeedAndMigrateAgain() {
      flyway(SCHEMA, "37").migrate()
      jdbc = jdbcFor(SCHEMA)
      tables.forEach { SepIndexSupport.seed(jdbc, it, ROWS) }

      // Control: the plans of the schema before the fix, on the rows the fixed schema is tested on.
      tables.forEach { table ->
        lookupColumns.forEach { column ->
          controlLookupPlans[table to column] =
            SepIndexSupport.plan(jdbc, SepIndexSupport.lookupSql(table, column), randomId())
        }
        controlListPlans[table] = SepIndexSupport.plan(jdbc, SepIndexSupport.listSql(table), "G42")
      }
      countsBefore = tables.associateWith { count(it) }
      schemaBefore = schemaSnapshot()

      migrationError = runCatching { flyway(SCHEMA).migrate() }.exceptionOrNull()
      tables.forEach { jdbc.execute("ANALYZE $it") }
    }

    @AfterAll
    @JvmStatic
    fun closeContexts() {
      contexts.forEach { it.close() }
      contexts.clear()
    }

    private val contexts =
      mutableListOf<org.springframework.context.ConfigurableApplicationContext>()

    private fun count(table: String): Int =
      jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun randomId() = UUID.randomUUID().toString()

    private fun catalog(table: String) = SepIndexSupport.catalog(jdbc, SCHEMA, table)

    private fun assertIndexed(plan: List<PlanNode>, table: String, index: String) {
      assertTrue(
        plan.any { it.index == index },
        "expected a scan of $index on $table, got $plan",
      )
      assertTrue(
        plan.none { it.type == "Seq Scan" && it.relation == table },
        "expected no Seq Scan on $table, got $plan",
      )
    }

    private fun assertScanned(plan: List<PlanNode>, table: String) {
      assertTrue(
        plan.any { it.type == "Seq Scan" && it.relation == table },
        "expected a Seq Scan on $table, got $plan",
      )
    }
  }

  // IDX-01

  @Test
  fun `V38 adds a valid btree index on transaction_id to both tables`() =
    assertLookupIndexInCatalog("transaction_id")

  @Test
  fun `V38 adds a valid btree index on stellar_transaction_id to both tables`() =
    assertLookupIndexInCatalog("stellar_transaction_id")

  @Test
  fun `V38 adds a valid btree index on external_transaction_id to both tables`() =
    assertLookupIndexInCatalog("external_transaction_id")

  private fun assertLookupIndexInCatalog(column: String) {
    assertNull(migrationError, "the migrations must complete")
    tables.forEach { table ->
      assertEquals(
        IndexInfo(valid = true, method = "btree", columns = column),
        catalog(table)[indexName(table, column)],
        "index on $column of $table",
      )
    }
  }

  // IDX-02

  @Test
  fun `a lookup by transaction_id scans its index on both tables`() =
    assertLookupUsesIndex("transaction_id")

  @Test
  fun `a lookup by stellar_transaction_id scans its index on both tables`() =
    assertLookupUsesIndex("stellar_transaction_id")

  @Test
  fun `a lookup by external_transaction_id scans its index on both tables`() =
    assertLookupUsesIndex("external_transaction_id")

  private fun assertLookupUsesIndex(column: String) =
    tables.forEach { table ->
      val plan = SepIndexSupport.plan(jdbc, SepIndexSupport.lookupSql(table, column), randomId())
      assertIndexed(plan, table, indexName(table, column))
    }

  // IDX-03

  @Test
  fun `control - the same lookups at V37 scan the whole table`() {
    tables.forEach { table ->
      lookupColumns.forEach { column ->
        assertScanned(controlLookupPlans.getValue(table to column), table)
      }
    }
  }

  // LIST-01

  @Test
  fun `V38 adds a valid btree index on web_auth_account and started_at to both tables`() {
    assertNull(migrationError, "the migrations must complete")
    tables.forEach { table ->
      assertEquals(
        IndexInfo(valid = true, method = "btree", columns = "web_auth_account,started_at"),
        catalog(table)[indexName(table, "web_auth_account_started_at")],
        "list index of $table",
      )
    }
  }

  @Test
  fun `the list query of one account scans the list index on both tables`() =
    tables.forEach { table ->
      val plan = SepIndexSupport.plan(jdbc, SepIndexSupport.listSql(table), "G42")
      assertIndexed(plan, table, indexName(table, "web_auth_account_started_at"))
    }

  // LIST-02

  @Test
  fun `control - the same list query at V37 scans the whole table`() =
    tables.forEach { assertScanned(controlListPlans.getValue(it), it) }

  // MIG-01

  @Test
  fun `V38 leaves the row counts unchanged`() {
    assertNull(migrationError, "the migrations must complete")
    assertEquals(tables.associateWith { ROWS }, countsBefore)
    assertEquals(countsBefore, tables.associateWith { count(it) })
  }

  @Test
  fun `V38 leaves columns and constraints unchanged`() {
    assertNull(migrationError, "the migrations must complete")
    assertEquals(schemaBefore, schemaSnapshot())
  }

  // MIG-02

  @Test
  fun `V38 completes on tables with duplicate and null values in the indexed columns`() {
    // The seed holds 50 rows with transaction_id 'dup', 25,000 null stellar and 16,666 null
    // external ids; V38 ran over them in the @BeforeAll.
    assertEquals(
      50,
      jdbc.queryForObject(
        "SELECT count(*) FROM sep24_transaction WHERE transaction_id = 'dup'",
        Int::class.java
      )
    )
    assertNull(migrationError, "V38 must not fail on duplicates or nulls: $migrationError")
  }

  @Test
  fun `V38 keeps an index an operator created under one of its names`() {
    val schema = "pre"
    flyway(schema, "37").migrate()
    val pre = jdbcFor(schema)
    val name = indexName("sep24_transaction", "transaction_id")
    pre.execute("CREATE INDEX $name ON sep24_transaction (transaction_id)")
    val oidBefore = pre.queryForObject("SELECT '$schema.$name'::regclass::oid", Long::class.java)

    flyway(schema).migrate()

    assertEquals(
      oidBefore,
      pre.queryForObject("SELECT '$schema.$name'::regclass::oid", Long::class.java)
    )
    val indexes = SepIndexSupport.catalog(pre, schema, "sep24_transaction")
    assertEquals(
      IndexInfo(valid = true, method = "btree", columns = "transaction_id"),
      indexes[name],
    )
    SepIndexSupport.indexColumns.keys
      .filter { it != "transaction_id" }
      .forEach { assertNotNull(indexes[indexName("sep24_transaction", it)], "missing $it") }
  }

  // EDGE-01

  @Test
  fun `the repository lookups return the stored row`() {
    val context =
      SepIndexSupport.context(
        org.springframework.jdbc.datasource.DriverManagerDataSource(
          SepIndexSupport.urlFor(postgres.jdbcUrl, SCHEMA),
          postgres.username,
          postgres.password
        ),
        "none",
      )
    contexts += context
    tables.forEach {
      jdbc.update(
        "INSERT INTO $it (id, transaction_id, stellar_transaction_id, external_transaction_id)" +
          " VALUES ('edge-$it', 'tx-$it', 'st-$it', 'ex-$it')"
      )
    }
    val repo24 = SepIndexSupport.repo24(context)
    val repo6 = SepIndexSupport.repo6(context)

    try {
      assertEquals(
        "edge-sep24_transaction",
        repo24.findOneByTransactionId("tx-sep24_transaction").id
      )
      assertEquals(
        "edge-sep24_transaction",
        repo24.findOneByStellarTransactionId("st-sep24_transaction").id
      )
      assertEquals(
        "edge-sep24_transaction",
        repo24.findOneByExternalTransactionId("ex-sep24_transaction").id
      )
      assertEquals("edge-sep6_transaction", repo6.findOneByTransactionId("tx-sep6_transaction").id)
      assertEquals(
        "edge-sep6_transaction",
        repo6.findOneByStellarTransactionId("st-sep6_transaction").id
      )
      assertEquals(
        "edge-sep6_transaction",
        repo6.findOneByExternalTransactionId("ex-sep6_transaction").id
      )
    } finally {
      // The count and schema tests compare against the seeded 50,000 rows.
      tables.forEach { jdbc.update("DELETE FROM $it WHERE id = 'edge-$it'") }
    }
  }

  @Test
  fun `an empty id still scans the index`() =
    tables.forEach { table ->
      val plan = SepIndexSupport.plan(jdbc, SepIndexSupport.lookupSql(table, "transaction_id"), "")
      assertIndexed(plan, table, indexName(table, "transaction_id"))
    }

  @Test
  fun `a muxed account still scans the list index`() {
    val muxed = "M" + "A".repeat(68)
    tables.forEach { table ->
      val plan = SepIndexSupport.plan(jdbc, SepIndexSupport.listSql(table), muxed)
      assertIndexed(plan, table, indexName(table, "web_auth_account_started_at"))
    }
  }
}
