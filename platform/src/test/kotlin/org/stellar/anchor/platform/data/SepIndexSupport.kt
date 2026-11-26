package org.stellar.anchor.platform.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jakarta.persistence.EntityManagerFactory
import java.util.function.Supplier
import javax.sql.DataSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter

/**
 * Catalog, plan and seeding helpers for the tests of the SEP-24/6 lookup indexes
 * (HackerOne #4240810).
 */
internal object SepIndexSupport {
  val tables = listOf("sep24_transaction", "sep6_transaction")

  /** Index name suffix by the column list it covers, in the order the index lists the columns. */
  val indexColumns =
    linkedMapOf(
      "transaction_id" to "transaction_id",
      "stellar_transaction_id" to "stellar_transaction_id",
      "external_transaction_id" to "external_transaction_id",
      "web_auth_account_started_at" to "web_auth_account,started_at",
    )

  fun indexName(table: String, suffix: String) = "idx_${table}_$suffix"

  data class IndexInfo(val valid: Boolean, val method: String, val columns: String)

  data class PlanNode(val type: String, val index: String?, val relation: String?)

  fun urlFor(baseUrl: String, schema: String): String =
    baseUrl + (if (baseUrl.contains("?")) "&" else "?") + "currentSchema=" + schema

  fun jdbc(baseUrl: String, user: String, password: String, schema: String) =
    JdbcTemplate(DriverManagerDataSource(urlFor(baseUrl, schema), user, password))

  /** Every index of [table] in [schema], by name. Primary keys and other constraints included. */
  fun catalog(jdbc: JdbcTemplate, schema: String, table: String): Map<String, IndexInfo> =
    jdbc
      .queryForList(
        """
        SELECT i.relname AS name, ix.indisvalid AS valid, am.amname AS method,
               string_agg(a.attname, ',' ORDER BY k.ord) AS columns
        FROM pg_index ix
        JOIN pg_class t ON t.oid = ix.indrelid
        JOIN pg_class i ON i.oid = ix.indexrelid
        JOIN pg_am am ON am.oid = i.relam
        JOIN pg_namespace n ON n.oid = t.relnamespace
        CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord)
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
        WHERE n.nspname = ? AND t.relname = ?
        GROUP BY i.relname, ix.indisvalid, am.amname
        """,
        schema,
        table,
      )
      .associate {
        it["name"] as String to
          IndexInfo(it["valid"] as Boolean, it["method"] as String, it["columns"] as String)
      }

  /** The plan nodes PostgreSQL chooses for [sql] (`EXPLAIN`, no execution), flattened. */
  fun plan(jdbc: JdbcTemplate, sql: String, vararg args: Any?): List<PlanNode> {
    val json = jdbc.queryForObject("EXPLAIN (FORMAT JSON) $sql", String::class.java, *args)!!
    val root = JsonParser.parseString(json).asJsonArray[0].asJsonObject.getAsJsonObject("Plan")
    val nodes = mutableListOf<PlanNode>()

    fun walk(node: JsonObject) {
      nodes +=
        PlanNode(
          node.get("Node Type").asString,
          node.get("Index Name")?.asString,
          node.get("Relation Name")?.asString,
        )
      (node.get("Plans") as? JsonArray)?.forEach { walk(it.asJsonObject) }
    }
    walk(root)
    return nodes
  }

  fun lookupSql(table: String, column: String) = "SELECT * FROM $table WHERE $column = ?"

  /**
   * The shape of the wallet's list query for a memo-less caller (`findTransactionsWithFilters`).
   */
  fun listSql(table: String) =
    "SELECT * FROM $table WHERE web_auth_account = ? AND web_auth_account_memo IS NULL" +
      " AND request_asset_code = 'USDC' AND kind = 'deposit'" +
      " AND started_at > now() - interval '30 days' AND started_at < now()" +
      " ORDER BY started_at DESC LIMIT 20"

  /**
   * Seeds [rows] rows with duplicate and null values in the lookup columns: every 1,000th
   * `transaction_id` is the same value, half the `stellar_transaction_id` and a third of the
   * `external_transaction_id` are null, and the rows spread over [rows]/10 accounts.
   */
  fun seed(jdbc: JdbcTemplate, table: String, rows: Int) {
    jdbc.update(
      """
      INSERT INTO $table (id, transaction_id, stellar_transaction_id, external_transaction_id,
                          web_auth_account, started_at, status, request_asset_code, kind)
      SELECT md5('id' || i),
             CASE WHEN i % 1000 = 0 THEN 'dup' ELSE md5('t' || i) END,
             CASE WHEN i % 2 = 0 THEN NULL ELSE md5('s' || i) END,
             CASE WHEN i % 3 = 0 THEN NULL ELSE 'e' || (i % 40000) END,
             'G' || (i % ${rows / 10}),
             now() - (i || ' seconds')::interval,
             'completed', 'USDC', 'deposit'
      FROM generate_series(1, ?) AS i
      """,
      rows,
    )
    jdbc.execute("ANALYZE $table")
  }

  /**
   * A JPA context over [dataSource] with the two SEP transaction entities and their repositories.
   */
  fun context(dataSource: DataSource, ddlAuto: String): AnnotationConfigApplicationContext {
    val context = AnnotationConfigApplicationContext()
    context.registerBean("dataSource", DataSource::class.java, Supplier { dataSource })
    context.registerBean(
      "entityManagerFactory",
      LocalContainerEntityManagerFactoryBean::class.java,
      Supplier {
        LocalContainerEntityManagerFactoryBean().apply {
          this.dataSource = context.getBean(DataSource::class.java)
          setManagedTypes(
            PersistenceManagedTypes.of(
              JdbcSep24Transaction::class.java.name,
              JdbcSep6Transaction::class.java.name,
            )
          )
          jpaVendorAdapter = HibernateJpaVendorAdapter()
          setJpaPropertyMap(
            mapOf(
              "hibernate.hbm2ddl.auto" to ddlAuto,
              // Spring Boot's default, which the platform runs with: `amountExpected` is a column
              // named `amount_expected`.
              "hibernate.physical_naming_strategy" to
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            )
          )
        }
      },
    )
    context.registerBean(
      "transactionManager",
      JpaTransactionManager::class.java,
      Supplier { JpaTransactionManager(context.getBean(EntityManagerFactory::class.java)) },
    )
    context.refresh()
    return context
  }

  /**
   * The real repository interface over [context]: the factory is given the custom fragment the
   * repositories extend, which component scanning would find in the application.
   */
  fun repo24(context: AnnotationConfigApplicationContext): JdbcSep24TransactionRepo {
    val em =
      SharedEntityManagerCreator.createSharedEntityManager(
        context.getBean(EntityManagerFactory::class.java)
      )
    return JpaRepositoryFactory(em)
      .getRepository(
        JdbcSep24TransactionRepo::class.java,
        RepositoryFragments.just(AllTransactionsRepositoryImpl<JdbcSep24Transaction>(em)),
      )
  }

  fun repo6(context: AnnotationConfigApplicationContext): JdbcSep6TransactionRepo {
    val em =
      SharedEntityManagerCreator.createSharedEntityManager(
        context.getBean(EntityManagerFactory::class.java)
      )
    return JpaRepositoryFactory(em)
      .getRepository(
        JdbcSep6TransactionRepo::class.java,
        RepositoryFragments.just(AllTransactionsRepositoryImpl<JdbcSep6Transaction>(em)),
      )
  }
}
