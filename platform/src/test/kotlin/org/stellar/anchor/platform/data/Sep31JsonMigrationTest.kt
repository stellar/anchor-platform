package org.stellar.anchor.platform.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Static checks of `V37__sep31_json_columns.sql`. The repo has no database in its unit tests, so
 * what the migration does to real rows (types, preserved values, the failure on invalid JSON) is
 * checked once on a PostgreSQL and recorded in the feature's `validation.md`.
 */
class Sep31JsonMigrationTest {
  private val statements: List<String> by lazy {
    val sql =
      javaClass.getResourceAsStream("/db/migration/V37__sep31_json_columns.sql")?.use {
        it.readBytes().toString(Charsets.UTF_8)
      }
    assertNotNull(sql, "V37__sep31_json_columns.sql must be on the classpath")
    sql!!.split(";").map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
  }

  private val alterColumn =
    Regex(
      "ALTER TABLE sep31_transaction ALTER COLUMN (\\w+) TYPE JSON USING (\\w+)::json",
      RegexOption.IGNORE_CASE
    )

  @Test
  fun `migration converts both columns to json with a cast`() {
    val converted = statements.map { alterColumn.matchEntire(it) }

    assertTrue(converted.all { it != null }, "every statement must be an ALTER ... TYPE JSON USING")
    // The cast reads the column it replaces: `USING fields::json`, never another expression.
    converted.forEach { assertEquals(it!!.groupValues[1], it.groupValues[2]) }
    assertEquals(
      setOf("fields", "required_info_updates"),
      converted.map { it!!.groupValues[1].lowercase() }.toSet()
    )
  }

  @Test
  fun `migration touches only the two columns`() {
    val columns = statements.map { alterColumn.matchEntire(it)?.groupValues?.get(1)?.lowercase() }

    assertEquals(listOf("fields", "required_info_updates"), columns.sortedBy { it })
    // Nothing but sep31_transaction is named, so no other table is altered.
    statements.forEach {
      assertTrue(it.contains("sep31_transaction", ignoreCase = true), "other table in: $it")
    }
  }

  @Test
  fun `migration holds exactly two statements`() {
    assertEquals(2, statements.size)
  }
}
