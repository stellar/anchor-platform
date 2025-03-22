package org.stellar.anchor.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TOIDTest {
  @ParameterizedTest
  @CsvSource("12345, 100, 50", "0, 0, 0", "1, 1, 1", "100000, 100000, 1000")
  fun `test TOID encode and decode()`(
    ledgerSequence: Long,
    transactionOrder: Int,
    operationOrder: Int
  ) {
    val id = TOID(ledgerSequence, transactionOrder, operationOrder)
    val encoded = id.encode()
    val parsedId = TOID.decode(encoded)

    assertEquals(id.ledgerSequence, parsedId.ledgerSequence)
    assertEquals(id.transactionOrder, parsedId.transactionOrder)
    assertEquals(id.operationOrder, parsedId.operationOrder)
  }

  @ParameterizedTest
  @CsvSource(
    "-1, 0, 0",
    "0, -1, 0",
    "0, 0, -1",
    "9223372036854775807, 0, 0",
    "0,1048576,0",
    "0,0,4097"
  )
  fun `test TOID encode with invalid values`(
    ledgerSequence: Long,
    transactionOrder: Int,
    operationOrder: Int
  ) {
    val id = TOID(ledgerSequence, transactionOrder, operationOrder)
    assertThrows<IllegalArgumentException> { id.encode() }
  }
}
