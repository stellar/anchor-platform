package org.stellar.anchor.platform.data

import jakarta.persistence.EntityManagerFactory
import java.util.function.Supplier
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.stellar.anchor.client.ClientConfig.ClientType
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.client.NonCustodialClient
import org.stellar.anchor.platform.controller.platform.ClientConfigRequest
import org.stellar.anchor.platform.service.ClientConfigService
import org.stellar.sdk.KeyPair
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * ANCHOR-1587 (HackerOne #4241206). With `spring.jpa.open-in-view=false` no `EntityManager` stays
 * bound to the request, so an entity returned by a repository call is detached and its lazy
 * collections can no longer be loaded. `JdbcClientConfig` keeps three `@ElementCollection`s that
 * `JdbcClientService` and `ClientConfigService` read after the repository call has returned. These
 * tests run both against a real PostgreSQL, with no entity manager bound to the thread, and read
 * the sets after each call returned. Needs Docker.
 */
@Testcontainers
class JdbcClientConfigDetachedPostgresTest {

  companion object {
    @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:15.2-alpine")

    private lateinit var context: AnnotationConfigApplicationContext

    @BeforeAll
    @JvmStatic
    fun setUp() {
      // The schema comes from the project's own migrations, so the test reads the real tables.
      Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
      context = buildContext()
    }

    @AfterAll
    @JvmStatic
    fun tearDown() {
      context.close()
    }

    private fun buildContext(): AnnotationConfigApplicationContext {
      val context = AnnotationConfigApplicationContext()
      context.registerBean(
        "dataSource",
        DataSource::class.java,
        Supplier {
          DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        },
      )
      context.registerBean(
        "entityManagerFactory",
        LocalContainerEntityManagerFactoryBean::class.java,
        Supplier {
          LocalContainerEntityManagerFactoryBean().apply {
            dataSource = context.getBean(DataSource::class.java)
            setManagedTypes(PersistenceManagedTypes.of(JdbcClientConfig::class.java.name))
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
        "jdbcClientConfigRepo",
        JpaRepositoryFactoryBean::class.java,
        JdbcClientConfigRepo::class.java,
      )
      context.refresh()
      return context
    }
  }

  private lateinit var jdbc: JdbcTemplate
  private lateinit var clientService: JdbcClientService
  private lateinit var configService: ClientConfigService

  private val custodialKey = KeyPair.random().accountId
  private val secondKey = KeyPair.random().accountId
  private val thirdKey = KeyPair.random().accountId
  private val account1 = KeyPair.random().accountId
  private val account2 = KeyPair.random().accountId

  @BeforeEach
  fun reset() {
    jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
    jdbc.execute("DELETE FROM client_config")
    val repo = context.getBean(JdbcClientConfigRepo::class.java)
    clientService = JdbcClientService(repo)
    configService = ClientConfigService(context.getBeanProvider(JdbcClientConfigRepo::class.java))
    // The point of the test: nothing keeps an entity manager open between calls.
    assertFalse(
      TransactionSynchronizationManager.hasResource(
        context.getBean(EntityManagerFactory::class.java)
      ),
      "an entity manager is bound to the thread",
    )
  }

  private fun custodialRequest(keys: Set<String>, destinations: Set<String> = emptySet()) =
    ClientConfigRequest().apply {
      type = ClientType.CUSTODIAL
      signingKeys = keys
      destinationAccounts = destinations
      setAllowAnyDestination(false)
    }

  private fun nonCustodialRequest(domains: Set<String>) =
    ClientConfigRequest().apply {
      type = ClientType.NONCUSTODIAL
      this.domains = domains
    }

  private fun column(table: String, column: String, client: String): Set<String> =
    jdbc
      .queryForList("SELECT $column FROM $table WHERE client_name = ?", String::class.java, client)
      .toSet()

  @Test
  fun `JdbcClientService returns the three sets of a loaded client with no bound entity manager`() {
    configService.upsert("MGI", custodialRequest(setOf(custodialKey), setOf(account1, account2)))
    configService.upsert("VIBRANT", nonCustodialRequest(setOf("vibrantapp.com", "vibrant.example")))

    val custodial = clientService.getClientConfigByName("MGI") as CustodialClient
    assertEquals(setOf(custodialKey), custodial.signingKeys.toSet())
    assertEquals(setOf(account1, account2), custodial.destinationAccounts.toSet())

    val nonCustodial = clientService.getClientConfigByName("VIBRANT") as NonCustodialClient
    assertEquals(setOf("vibrantapp.com", "vibrant.example"), nonCustodial.domains.toSet())

    val bySigningKey = clientService.getClientConfigBySigningKey(custodialKey)
    assertEquals(setOf(custodialKey), bySigningKey.signingKeys.toSet())
    assertEquals(setOf(account1, account2), bySigningKey.destinationAccounts.toSet())

    // The lookup query joins the collection it filters on, so the set it returns holds at least the
    // matched domain. That is how it behaves with or without a bound entity manager.
    val byDomain = clientService.getClientConfigByDomain("vibrant.example")
    assertEquals("VIBRANT", byDomain.name)
    assertTrue(byDomain.domains.contains("vibrant.example"))

    assertEquals(
      setOf(custodialKey),
      clientService.custodialClients.single().signingKeys.toSet(),
    )
    assertEquals(
      setOf("vibrantapp.com", "vibrant.example"),
      clientService.nonCustodialClients.single().domains.toSet(),
    )
    assertEquals(2, clientService.allClients.size)
  }

  @Test
  fun `ClientConfigService adds then removes a destination account and a signing key`() {
    configService.upsert("MGI", custodialRequest(setOf(custodialKey), setOf(account1)))

    configService.addDestinationAccount("MGI", account2)
    assertEquals(
      setOf(account1, account2),
      column("client_destination_account", "destination_account", "MGI")
    )

    configService.removeDestinationAccount("MGI", account1)
    assertEquals(
      setOf(account2),
      column("client_destination_account", "destination_account", "MGI")
    )

    configService.addSigningKey("MGI", secondKey)
    assertEquals(setOf(custodialKey, secondKey), column("client_signing_key", "signing_key", "MGI"))

    configService.removeSigningKey("MGI", custodialKey)
    assertEquals(setOf(secondKey), column("client_signing_key", "signing_key", "MGI"))
  }

  @Test
  fun `ClientConfigService upsert replaces the three sets of an existing client`() {
    configService.upsert("MGI", custodialRequest(setOf(custodialKey), setOf(account1)))

    val response = configService.upsert("MGI", custodialRequest(setOf(thirdKey), setOf(account2)))

    assertEquals(setOf(thirdKey), response.signingKeys)
    assertEquals(setOf(account2), response.destinationAccounts)
    assertEquals(setOf(thirdKey), column("client_signing_key", "signing_key", "MGI"))
    assertEquals(
      setOf(account2),
      column("client_destination_account", "destination_account", "MGI")
    )
    assertEquals(emptySet<String>(), column("client_domain", "domain", "MGI"))
  }
}
