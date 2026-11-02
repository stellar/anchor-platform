package org.stellar.anchor.platform.data

import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.Field
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.AssetInfo
import org.stellar.anchor.api.asset.Sep31Info
import org.stellar.anchor.util.GsonUtils

class JdbcSep31TransactionTest {
  // Hibernate writes a @JdbcTypeCode(SqlTypes.JSON) attribute with Jackson, built like this.
  private val jackson = ObjectMapper().findAndRegisterModules()

  private fun column(name: String): Field = JdbcSep31Transaction::class.java.getDeclaredField(name)

  /** Writes and reads a value the way the JSON column does, with the entity's declared type. */
  private fun <T> roundTrip(attribute: String, value: T): T? {
    val type = jackson.typeFactory.constructType(column(attribute).genericType)
    val json = jackson.writeValueAsString(value)
    return jackson.readValue(json, type)
  }

  @Test
  fun `fields and required_info_updates are mapped as json`() {
    for ((attribute, columnName) in
      mapOf("fields" to "fields", "requiredInfoUpdates" to "required_info_updates")) {
      val field = column(attribute)
      assertEquals(
        SqlTypes.JSON,
        field.getAnnotation(JdbcTypeCode::class.java)?.value,
        "$attribute must use the JSON JDBC type"
      )
      assertEquals(
        columnName,
        field.getAnnotation(jakarta.persistence.Column::class.java)?.name,
        "$attribute must map to $columnName"
      )
      assertNull(
        field.getAnnotation(jakarta.persistence.Transient::class.java),
        "$attribute must be persisted"
      )
    }
    val accessors = JdbcSep31Transaction::class.java.declaredMethods.map { it.name }
    assertTrue(
      accessors.none { it.endsWith("FieldsJson") || it.endsWith("RequiredInfoUpdatesJson") },
      "no string accessor may stand between the entity and the JSON column: $accessors"
    )
  }

  @Test
  fun `a 300-character field value survives a jackson round trip`() {
    val fields = mapOf("receiver_account_number" to "a".repeat(300), "other" to "b")

    assertEquals(fields, roundTrip("fields", fields))
  }

  @Test
  fun `non-ASCII field values survive a jackson round trip`() {
    val fields =
      mapOf(
        "name" to "José Ñandú",
        "city" to "東京都 \uD83D\uDE80",
        "note" to "quote \" and \\ and \n"
      )

    assertEquals(fields, roundTrip("fields", fields))
  }

  @Test
  fun `an empty map and an absent value survive a jackson round trip`() {
    assertEquals(emptyMap<String, String>(), roundTrip("fields", emptyMap<String, String>()))
    assertNull(roundTrip<Map<String, String>?>("fields", null))
    assertNull(roundTrip<Sep31Info.Fields?>("requiredInfoUpdates", null))
  }

  @Test
  fun `required_info_updates keep descriptions, choices and optional flags through a jackson round trip`() {
    val updates = Sep31Info.Fields()
    updates.transaction =
      (1..10).associate {
        "field_$it" to
          AssetInfo.Field.builder()
            .description("Description of the field number $it, long enough to pass 255 characters")
            .choices(if (it % 2 == 0) listOf("a", "b") else null)
            .optional(it % 3 == 0)
            .build()
      }

    assertEquals(updates, roundTrip("requiredInfoUpdates", updates))
  }

  @Test
  fun `values written by the old code read into the same objects`() {
    val gson = GsonUtils.getInstance()
    val fields = mapOf("receiver_account_number" to "a9999")
    val updates = Sep31Info.Fields()
    updates.transaction =
      mapOf("receiver_account_number" to AssetInfo.Field.builder().description("Account").build())

    val fieldsType = jackson.typeFactory.constructType(column("fields").genericType)
    val updatesType = jackson.typeFactory.constructType(column("requiredInfoUpdates").genericType)

    assertEquals(fields, jackson.readValue<Map<String, String>>(gson.toJson(fields), fieldsType))
    assertEquals(updates, jackson.readValue<Sep31Info.Fields>(gson.toJson(updates), updatesType))
    // The old code wrote the text `null` for an absent value.
    assertNull(
      jackson.readValue<Map<String, String>?>(gson.toJson(null as Map<String, String>?), fieldsType)
    )
  }

  private val refundsJsonNoRefundPayment =
    """
            {
              "amount_refunded": "10",
              "amount_fee": "5",
              "refundPayments": null
            }
        """
      .trimIndent()

  private val refundsJsonWithRefundPayment =
    """
            {
              "amount_refunded": "10",
              "amount_fee": "5",
              "payments": [
                {
                  "id": "1",
                  "amount": "5",
                  "fee": "1"
                },
                {
                  "id": "2",
                  "amount": "5",
                  "fee": "4"
                }
              ]
            }
        """
      .trimIndent()
}
