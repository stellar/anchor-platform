package org.stellar.anchor.platform.data

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query

/**
 * `nonce.expires_at` holds UTC wall-clock time, so no query on [JdbcNonceRepo] may read the
 * database clock or session time zone: Hibernate renders `CURRENT_TIMESTAMP` as `localtimestamp`,
 * which follows the JVM's default zone. Instants must be bound from a `Clock` instead.
 */
class JdbcNonceRepoQueryGuardTest {

  private val databaseClockFunctions =
    Regex(
      "\\b(CURRENT_TIMESTAMP|CURRENT_DATE|CURRENT_TIME|LOCAL_DATETIME|LOCAL_TIME|LOCALTIMESTAMP)\\b|\\bNOW\\s*\\(",
      RegexOption.IGNORE_CASE,
    )

  @Test
  fun `no JdbcNonceRepo query calls the database clock or session time zone`() {
    val queries =
      JdbcNonceRepo::class
        .java
        .methods
        .mapNotNull { method -> method.getAnnotation(Query::class.java)?.let { method.name to it } }
        .map { (name, query) -> name to query.value }

    assertTrue(queries.isNotEmpty(), "JdbcNonceRepo should declare @Query methods to inspect")

    val offending = queries.filter { (_, sql) -> databaseClockFunctions.containsMatchIn(sql) }
    assertTrue(offending.isEmpty(), "Queries reading the database clock: $offending")
  }
}
