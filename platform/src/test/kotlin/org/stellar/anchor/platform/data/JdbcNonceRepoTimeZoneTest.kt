package org.stellar.anchor.platform.data

import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.function.Supplier
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.stellar.anchor.auth.NonceManager
import org.stellar.anchor.platform.job.NonceCleanupJob
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * `nonce.expires_at` is `TIMESTAMP WITHOUT TIME ZONE` holding UTC wall-clock time. These tests run
 * the real repository, store, [NonceManager] and [NonceCleanupJob] against a real PostgreSQL once
 * per JVM default zone, because the defect (a cleanup that compared the column with the session's
 * local clock) only exists in how Hibernate renders the query and how the driver sets the session
 * zone from the JVM default. Needs Docker.
 *
 * The scenarios use the offsets of HackerOne #4195289 (SEP-10 replay: claim, cleanup 12 min 16 s
 * later, replay 4 s after that) and #4161826 (SEP-45 refusal). They are anchored to the current
 * time rather than to fixed dates: the defective query read the database's own clock, so only a
 * timeline whose "now" coincides with that clock (in UTC) can tell a zone error from a stale
 * fixture. The fixed code compares against the test clock only, so the result does not depend on
 * the anchor.
 */
@Testcontainers
class JdbcNonceRepoTimeZoneTest {

  companion object {
    @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:15.2-alpine")

    // UTC is the control; Berlin is ahead of UTC (the report's zone); Kolkata has a half-hour
    // offset; Honolulu is behind UTC.
    private val zones = listOf("UTC", "Europe/Berlin", "Asia/Kolkata", "Pacific/Honolulu")

    private val contexts = mutableMapOf<String, AnnotationConfigApplicationContext>()

    @BeforeAll
    @JvmStatic
    fun migrate() {
      // The schema comes from the project's own migrations, so the test reads the real table.
      Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    }

    @AfterAll
    @JvmStatic
    fun closeContexts() {
      contexts.values.forEach { it.close() }
      contexts.clear()
    }

    private val utcWallClockFormat =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    private fun utcWallClock(instant: Instant): String = utcWallClockFormat.format(instant)
  }

  private class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
  }

  /** Everything a scenario needs, built while the JVM default zone is the zone under test. */
  private class Rig(val context: AnnotationConfigApplicationContext) {
    /** The instant a scenario's cleanup runs at; every other instant is an offset from it. */
    val base: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val clock = MutableClock(base)
    val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
    val store = JdbcNonceStore(context.getBean(JdbcNonceRepo::class.java))
    val nonceManager = NonceManager(store, clock)
    val cleanupJob = NonceCleanupJob(store, clock)

    fun at(offsetSeconds: Long): Instant = base.plusSeconds(offsetSeconds)

    fun rowCount(id: String): Int =
      jdbc.queryForObject("SELECT count(*) FROM nonce WHERE id = ?", Int::class.java, id)!!

    fun cleanupAt(instant: Instant) {
      clock.now = instant
      cleanupJob.cleanup()
    }
  }

  @TestFactory
  fun `nonce cleanup compares expiry in UTC whatever the JVM default zone`(): List<DynamicNode> =
    zones.map { zone ->
      DynamicContainer.dynamicContainer(
        zone,
        listOf(
          scenario(zone, "SEP-10 claim survives cleanup and a second claim is refused") { rig ->
            // #4195289: claim, cleanup 12 min 16 s later (736 s), replay 4 s after the cleanup.
            val claimedAt = rig.at(-736)
            val maxTime = claimedAt.plusSeconds(900)
            rig.clock.now = claimedAt
            assertTrue(rig.nonceManager.claim("hash-1", 3600, maxTime))
            // The write path stores UTC wall-clock time, in every zone.
            assertEquals(
              utcWallClock(claimedAt.plusSeconds(3600)),
              rig.jdbc.queryForObject(
                "SELECT to_char(expires_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM nonce WHERE id = ?",
                String::class.java,
                "hash-1",
              ),
            )

            rig.cleanupAt(rig.at(0))
            assertEquals(1, rig.rowCount("hash-1"))

            rig.clock.now = rig.at(4)
            assertFalse(rig.nonceManager.claim("hash-1", 3600, maxTime))
          },
          scenario(zone, "SEP-45 nonce survives cleanup and can still be redeemed") { rig ->
            // #4161826: a 300 s nonce, cleanup 180 s after creation, redeemed 240 s after creation.
            rig.clock.now = rig.at(-180)
            val nonce = rig.nonceManager.create(300)

            rig.cleanupAt(rig.at(0))
            assertEquals(1, rig.rowCount(nonce.id))

            rig.clock.now = rig.at(60)
            assertTrue(rig.nonceManager.verifyAndUse(nonce.id))
          },
          scenario(zone, "cleanup one second after expiry deletes exactly the expired row") { rig ->
            rig.clock.now = rig.at(-601)
            // "expired" expires at base - 1 s; "live" at base + 1199 s.
            assertTrue(rig.nonceManager.claim("expired", 600, rig.at(-601)))
            assertTrue(rig.nonceManager.claim("live", 1800, rig.at(-601)))

            rig.cleanupAt(rig.at(0))

            assertEquals(0, rig.rowCount("expired"))
            assertEquals(1, rig.rowCount("live"))
          },
          scenario(zone, "cleanup at the exact expiry instant keeps the row") { rig ->
            rig.clock.now = rig.at(-600)
            // Expires exactly at base.
            assertTrue(rig.nonceManager.claim("edge", 600, rig.at(-600)))

            rig.cleanupAt(rig.at(0))

            assertEquals(1, rig.rowCount("edge"))
          },
        ),
      )
    }

  private fun scenario(zone: String, name: String, body: (Rig) -> Unit): DynamicTest =
    DynamicTest.dynamicTest(name) {
      val previous = TimeZone.getDefault()
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      try {
        val rig = Rig(contexts.getOrPut(zone) { buildContext() })
        rig.jdbc.execute("DELETE FROM nonce")
        assertSessionZoneIs(rig, zone)
        body(rig)
      } finally {
        TimeZone.setDefault(previous)
      }
    }

  /** A zone that failed to apply must not let a scenario pass without actually exercising it. */
  private fun assertSessionZoneIs(rig: Rig, zone: String) {
    val sessionOffsetSeconds =
      rig.jdbc.queryForObject("SELECT extract(timezone FROM now())::int", Int::class.java)!!
    val expectedOffsetSeconds =
      TimeZone.getTimeZone(zone).getOffset(System.currentTimeMillis()) / 1000
    assertEquals(expectedOffsetSeconds, sessionOffsetSeconds, "database session zone for $zone")
  }

  private fun buildContext(): AnnotationConfigApplicationContext {
    val context = AnnotationConfigApplicationContext()
    context.registerBean(
      "dataSource",
      DataSource::class.java,
      Supplier {
        // A new connection per use: the session zone follows the JVM default at that moment.
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
      },
    )
    context.registerBean(
      "entityManagerFactory",
      LocalContainerEntityManagerFactoryBean::class.java,
      Supplier {
        LocalContainerEntityManagerFactoryBean().apply {
          dataSource = context.getBean(DataSource::class.java)
          setManagedTypes(PersistenceManagedTypes.of(JdbcNonce::class.java.name))
          jpaVendorAdapter = HibernateJpaVendorAdapter()
        }
      },
    )
    context.registerBean(
      "transactionManager",
      JpaTransactionManager::class.java,
      Supplier { JpaTransactionManager(context.getBean(EntityManagerFactory::class.java)) },
    )
    context.registerBean(
      "jdbcNonceRepo",
      JpaRepositoryFactoryBean::class.java,
      JdbcNonceRepo::class.java,
    )
    context.refresh()
    return context
  }
}
