package org.stellar.anchor.api.shared

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert

class StellarTransactionTest {
  companion object {
    private const val stellarUSDC =
      "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  }

  private val createdAt = Instant.ofEpochMilli(1690415946788L)
  private val mockPayment1 =
    StellarPayment.builder()
      .id("1111")
      .amount(Amount("100.0000", stellarUSDC))
      .paymentType(StellarPayment.Type.PAYMENT)
      .sourceAccount("GAS4OW4HKJCC2D6VWUHVFR3MJRRVQBXBFQ3LCZJXBR7TWOOBJWE4SRWZ")
      .destinationAccount("GBQC7NCZMQIPWN6ASUJYIDKDPRK34IOIZNQE5WOHPQH536VMOMQVJTN7")
      .build()
  private val mockPayment2 =
    StellarPayment.builder()
      .id("2222")
      .amount(Amount("200.0000", stellarUSDC))
      .paymentType(StellarPayment.Type.PAYMENT)
      .sourceAccount("GAS4OW4HKJCC2D6VWUHVFR3MJRRVQBXBFQ3LCZJXBR7TWOOBJWE4SRWZ")
      .destinationAccount("GBQC7NCZMQIPWN6ASUJYIDKDPRK34IOIZNQE5WOHPQH536VMOMQVJTN7")
      .build()
  private val mockPayment3 =
    StellarPayment.builder()
      .id("3333")
      .amount(Amount("300.0000", stellarUSDC))
      .paymentType(StellarPayment.Type.PAYMENT)
      .sourceAccount("GAS4OW4HKJCC2D6VWUHVFR3MJRRVQBXBFQ3LCZJXBR7TWOOBJWE4SRWZ")
      .destinationAccount("GBQC7NCZMQIPWN6ASUJYIDKDPRK34IOIZNQE5WOHPQH536VMOMQVJTN7")
      .build()

  @Test
  fun `test addOrUpdateTransactions with empty tx list`() {
    var txList: List<StellarTransaction>? = null

    txList =
      StellarTransaction.addOrUpdateTransactions(
        txList,
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    val wantTxList =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    assertEquals(wantTxList, txList)
  }

  @Test
  fun `test addOrUpdateTransactions with existing identical tx`() {
    var txList: List<StellarTransaction>? =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    txList =
      StellarTransaction.addOrUpdateTransactions(
        txList,
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    val wantTxList =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    assertEquals(wantTxList, txList)
  }

  @Test
  fun `test addOrUpdateTransactions updating existing tx and payment`() {
    var txList: List<StellarTransaction>? =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .payments(listOf(StellarPayment.builder().id("1111").build()))
          .build()
      )

    txList =
      StellarTransaction.addOrUpdateTransactions(
        txList,
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    val wantTxList =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    assertEquals(wantTxList, txList)
  }

  @Test
  fun `test addOrUpdateTransactions updating existing tx by adding 2 payments`() {
    var txList: List<StellarTransaction>? =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    txList =
      StellarTransaction.addOrUpdateTransactions(
        txList,
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment2, mockPayment3))
          .build()
      )

    val wantTxList =
      listOf(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1, mockPayment2, mockPayment3))
          .build()
      )

    assertEquals(wantTxList, txList)
  }

  @Test
  fun `test addOrUpdateTransactions by adding 2 tx`() {
    var txList: List<StellarTransaction>? =
      listOf(
        StellarTransaction.builder()
          .id("A")
          .payments(listOf(StellarPayment.builder().id("1111").build()))
          .build()
      )

    txList =
      StellarTransaction.addOrUpdateTransactions(
        txList,
        StellarTransaction.builder()
          .id("B")
          .memo("my-memo-b")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment2))
          .build(),
        StellarTransaction.builder()
          .id("C")
          .memo("my-memo-c")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment3))
          .build()
      )

    val wantTxList =
      listOf(
        StellarTransaction.builder()
          .id("A")
          .payments(listOf(StellarPayment.builder().id("1111").build()))
          .build(),
        StellarTransaction.builder()
          .id("B")
          .memo("my-memo-b")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment2))
          .build(),
        StellarTransaction.builder()
          .id("C")
          .memo("my-memo-c")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment3))
          .build()
      )

    assertEquals(wantTxList, txList)
  }

  @Test
  fun `test Kafka serialization`() {
    // Kafka uses Jackson for serialization
    val mapper = ObjectMapper()
    val module = JavaTimeModule()
    mapper.registerModule(module)

    val expected =
      """
        {
            "id": "2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300",
            "memo": "my-memo",
            "envelope": "here_comes_the_envelope",
            "payments": [
                {
                    "id": "1111",
                    "amount": {
                        "amount": "100.0000",
                        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                    },
                    "payment_type": "payment",
                    "source_account": "GAS4OW4HKJCC2D6VWUHVFR3MJRRVQBXBFQ3LCZJXBR7TWOOBJWE4SRWZ",
                    "destination_account": "GBQC7NCZMQIPWN6ASUJYIDKDPRK34IOIZNQE5WOHPQH536VMOMQVJTN7"
                }
            ],
            "memo_type": "text",
            "created_at": 1690415946.788000000
        }
    """.trimIndent()
    val actual =
      mapper.writeValueAsString(
        StellarTransaction.builder()
          .id("2b862ac297c93e2db43fc58d407cc477396212bce5e6d5f61789f963d5a11300")
          .memo("my-memo")
          .memoType("text")
          .createdAt(createdAt)
          .envelope("here_comes_the_envelope")
          .payments(listOf(mockPayment1))
          .build()
      )

    JSONAssert.assertEquals(expected, actual, true)
  }
}
