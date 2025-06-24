package org.stellar.anchor.platform.integrationtest

import java.time.Instant
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.JSONCompareMode.LENIENT
import org.springframework.data.domain.Sort.Direction
import org.springframework.data.domain.Sort.Direction.DESC
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_31
import org.stellar.anchor.api.platform.PlatformTransactionData.builder
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.client.TYPE_MULTIPART_FORM_DATA
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.platform.printRequest
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.debug
import org.stellar.anchor.util.StringHelper.json

lateinit var savedTxn: Sep31GetTransactionResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Sep31Tests : IntegrationTestBase(TestConfig()) {
  private val sep12Client: Sep12Client = Sep12Client(toml.getString("KYC_SERVER"), this.token.token)
  private val sep31Client: Sep31Client =
    Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), this.token.token)
  private val sep38Client: Sep38Client =
    Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), this.token.token)
  private val platformApiClient: PlatformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)

  @Test
  fun `test info endpoint`() {
    printRequest("Calling GET /info")
    val info = sep31Client.getInfo()
    JSONAssert.assertEquals(expectedSep31Info, gson.toJson(info), JSONCompareMode.STRICT)
  }

  @Test
  @Order(30)
  fun `test post and get transactions`() {
    val (senderCustomer, receiverCustomer) = mkCustomers()

    val postTxResponse = createTx(senderCustomer, receiverCustomer)

    // GET Sep31 transaction
    savedTxn = sep31Client.getTransaction(postTxResponse.id)
    JSONAssert.assertEquals(expectedTxn, json(savedTxn), LENIENT)
    assertEquals(postTxResponse.id, savedTxn.transaction.id)
    assertEquals(PENDING_RECEIVER.status, savedTxn.transaction.status)
  }

  private fun mkCustomers(): Pair<Sep12PutCustomerResponse, Sep12PutCustomerResponse> {
    // Create sender customer
    val senderCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    val senderCustomer = sep12Client.putCustomer(senderCustomerRequest)

    // Create receiver customer
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = sep12Client.putCustomer(receiverCustomerRequest)

    return senderCustomer!! to receiverCustomer!!
  }

  fun createTx(
    senderCustomer: Sep12PutCustomerResponse,
    receiverCustomer: Sep12PutCustomerResponse
  ): Sep31PostTransactionResponse {
    // Create asset quote
    val quote =
      sep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )

    // POST Sep31 transaction
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.senderId = senderCustomer.id
    txnRequest.receiverId = receiverCustomer.id
    txnRequest.quoteId = quote.id
    val postTxResponse = sep31Client.postTransaction(txnRequest)
    return postTxResponse
  }

  @Test
  @Order(20)
  fun `test transactions`() {
    val (senderCustomer, receiverCustomer) = mkCustomers()

    val tx1 = createTx(senderCustomer, receiverCustomer)
    val tx2 = createTx(senderCustomer, receiverCustomer)
    val tx3 = createTx(senderCustomer, receiverCustomer)

    val all = listOf(tx1, tx2, tx3)

    debug("Created transactions ${tx1.id} ${tx2.id} ${tx3.id}")

    // Basic test
    val txs = getTransactions(pageSize = 1000)
    assertOrderCorrect(all, txs.records)

    // Order test
    val descTxs =
      getTransactions(
        order = DESC,
      )
    assertOrderCorrect(all.reversed(), descTxs.records)

    patchForTest(tx3, tx2, tx1)

    // OrderBy test
    var orderByTxs =
      getTransactions(orderBy = TransactionsOrderBy.TRANSFER_RECEIVED_AT, pageSize = 1000)
    assertOrderCorrect(listOf(tx2, tx3, tx1), orderByTxs.records)

    var orderByDesc =
      getTransactions(
        orderBy = TransactionsOrderBy.TRANSFER_RECEIVED_AT,
        order = DESC,
        pageSize = 1000
      )
    assertOrderCorrect(listOf(tx3, tx2, tx1), orderByDesc.records)

    orderByTxs =
      getTransactions(orderBy = TransactionsOrderBy.USER_ACTION_REQUIRED_BY, pageSize = 1000)
    assertOrderCorrect(listOf(tx1, tx2, tx3), orderByTxs.records)

    orderByDesc =
      getTransactions(
        orderBy = TransactionsOrderBy.USER_ACTION_REQUIRED_BY,
        order = DESC,
        pageSize = 1000
      )
    assertOrderCorrect(listOf(tx2, tx1, tx3), orderByDesc.records)

    // Statuses test
    val statusesTxs = getTransactions(statuses = listOf(PENDING_SENDER, REFUNDED), pageSize = 1000)
    assertOrderCorrect(listOf(tx1, tx2), statusesTxs.records)
  }

  private fun getTransactions(
    order: Direction? = null,
    orderBy: TransactionsOrderBy? = null,
    statuses: List<SepTransactionStatus>? = null,
    pageSize: Int? = null,
    pageNumber: Int? = null
  ): GetTransactionsResponse {
    return platformApiClient.getTransactions(
      TransactionsSeps.SEP_31,
      orderBy,
      order,
      statuses,
      pageSize,
      pageNumber
    )
  }

  private fun assertOrderCorrect(
    txs: List<Sep31PostTransactionResponse>,
    records: MutableList<GetTransactionResponse>
  ) {
    assertTrue(txs.size <= records.size)

    val txIds = txs.stream().map { it.id }.toList()
    assertEquals(
      txIds.toString(),
      records.stream().map { it.id }.filter { txIds.contains(it) }.toList().toString(),
      "Incorrect order of transactions"
    )
  }

  private fun patchForTest(
    tx3: Sep31PostTransactionResponse,
    tx2: Sep31PostTransactionResponse,
    tx1: Sep31PostTransactionResponse
  ) {
    platformApiClient.patchTransaction(
      PatchTransactionsRequest.builder()
        .records(
          listOf(
            PatchTransactionRequest(
              builder().id(tx3.id).transferReceivedAt(Instant.now()).status(COMPLETED).build()
            ),
            PatchTransactionRequest(
              builder()
                .id(tx2.id)
                .transferReceivedAt(Instant.now().minusSeconds(12345))
                .userActionRequiredBy(Instant.now().plusSeconds(10))
                .status(REFUNDED)
                .build()
            ),
            PatchTransactionRequest(
              builder()
                .id(tx1.id)
                .userActionRequiredBy(Instant.now())
                .status(PENDING_SENDER)
                .build()
            )
          )
        )
        .build()
    )
  }

  @Test
  fun testBadAsset() {
    val customer =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    val pr = sep12Client.putCustomer(customer)

    // Post Sep31 transaction.
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.assetCode = "bad-asset-code"
    txnRequest.receiverId = pr!!.id
    assertThrows<SepException> { sep31Client.postTransaction(txnRequest) }
  }

  @Test
  @Order(40)
  fun `test patch, get and compare`() {
    val patch = gson.fromJson(patchRequest, PatchTransactionsRequest::class.java)
    // create patch request and patch
    patch.records[0].transaction.id = savedTxn.transaction.id
    platformApiClient.patchTransaction(patch)

    // check if the patched transactions are as expected
    var afterPatch = platformApiClient.getTransaction(savedTxn.transaction.id)
    assertEquals(afterPatch.id, savedTxn.transaction.id)
    JSONAssert.assertEquals(expectedAfterPatch, json(afterPatch), LENIENT)

    // Test patch idempotency
    afterPatch = platformApiClient.getTransaction(savedTxn.transaction.id)
    assertEquals(afterPatch.id, savedTxn.transaction.id)
    JSONAssert.assertEquals(expectedAfterPatch, json(afterPatch), LENIENT)
  }

  @Test
  fun `test bad requests`() {
    // Create sender customer
    val senderCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    val senderCustomer = sep12Client.putCustomer(senderCustomerRequest, TYPE_MULTIPART_FORM_DATA)

    // Create receiver customer
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = sep12Client.putCustomer(receiverCustomerRequest)
    val quote =
      sep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )

    // POST SEP-31 transaction
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.senderId = senderCustomer!!.id
    txnRequest.receiverId = receiverCustomer!!.id
    txnRequest.quoteId = quote.id
    val postTxResponse = sep31Client.postTransaction(txnRequest)

    // GET platformAPI transaction
    val getTxResponse = platformApiClient.getTransaction(postTxResponse.id)
    assertEquals(postTxResponse.id, getTxResponse.id)
    assertEquals(PENDING_RECEIVER, getTxResponse.status)
    assertEquals(txnRequest.amount, getTxResponse.amountIn.amount)
    assertTrue(getTxResponse.amountIn.asset.contains(txnRequest.assetCode))
    assertEquals(SEP_31, getTxResponse.sep)
    assertNull(getTxResponse.completedAt)
    assertNotNull(getTxResponse.startedAt)
    assertTrue(getTxResponse.updatedAt >= getTxResponse.startedAt)

    // Modify the customer by erasing its clabe_number to simulate an invalid clabe_number
    sep12Client.invalidateCustomerClabe(receiverCustomer.id)
    var updatedReceiverCustomer = sep12Client.getCustomer(receiverCustomer.id, "sep31-receiver")
    assertEquals(Sep12Status.NEEDS_INFO, updatedReceiverCustomer?.status)
    assertNotNull(updatedReceiverCustomer?.fields?.get("clabe_number"))
    assertNull(updatedReceiverCustomer?.providedFields?.get("clabe_number"))

    // PATCH {platformAPI}/transaction status to PENDING_CUSTOMER_INFO_UPDATE, since the
    // clabe_number
    // was invalidated.
    var patchTxRequest =
      PatchTransactionRequest.builder()
        .transaction(
          builder()
            .id(getTxResponse.id)
            .status(PENDING_CUSTOMER_INFO_UPDATE)
            .message("The receiving customer clabe_number is invalid!")
            .build()
        )
        .build()
    var patchTxResponse =
      platformApiClient.patchTransaction(
        PatchTransactionsRequest.builder().records(listOf(patchTxRequest)).build()
      )
    assertEquals(1, patchTxResponse.records.size)
    var patchedTx = patchTxResponse.records[0]
    assertEquals(getTxResponse.id, patchedTx.id)
    assertEquals(PENDING_CUSTOMER_INFO_UPDATE, patchedTx.status)
    assertEquals(SEP_31, patchedTx.sep)
    assertEquals("The receiving customer clabe_number is invalid!", patchedTx.message)
    assertTrue(patchedTx.updatedAt > patchedTx.startedAt)

    // GET SEP-31 transaction should return PENDING_CUSTOMER_INFO_UPDATE with a message
    var gotSep31TxResponse = sep31Client.getTransaction(postTxResponse.id)
    assertEquals(postTxResponse.id, gotSep31TxResponse.transaction.id)
    assertEquals(PENDING_CUSTOMER_INFO_UPDATE.status, gotSep31TxResponse.transaction.status)
    assertEquals(
      "The receiving customer clabe_number is invalid!",
      gotSep31TxResponse.transaction.requiredInfoMessage
    )
    assertNull(gotSep31TxResponse.transaction.completedAt)

    // PUT sep12/customer with the correct clabe_number
    sep12Client.putCustomer(
      Sep12PutCustomerRequest.builder()
        .transactionId(getTxResponse.id)
        .type("sep31-receiver")
        .clabeNumber("5678")
        .build()
    )
    updatedReceiverCustomer = sep12Client.getCustomer(receiverCustomer.id, "sep31-receiver")
    assertEquals(Sep12Status.ACCEPTED, updatedReceiverCustomer?.status)
    assertNull(updatedReceiverCustomer?.fields?.get("clabe_number"))
    assertNotNull(updatedReceiverCustomer?.providedFields?.get("clabe_number"))

    // PATCH {platformAPI}/transaction status to COMPLETED, since the clabe_number was updated
    // correctly.
    patchTxRequest =
      PatchTransactionRequest.builder()
        .transaction(
          builder().id(getTxResponse.id).completedAt(Instant.now()).status(COMPLETED).build()
        )
        .build()
    patchTxResponse =
      platformApiClient.patchTransaction(
        PatchTransactionsRequest.builder().records(listOf(patchTxRequest)).build()
      )
    assertEquals(1, patchTxResponse.records.size)
    patchedTx = patchTxResponse.records[0]
    assertEquals(getTxResponse.id, patchedTx.id)
    assertEquals(COMPLETED, patchedTx.status)
    assertEquals(SEP_31, patchedTx.sep)
    assertTrue(patchedTx.startedAt < patchedTx.updatedAt)
    assertNotNull(patchedTx.completedAt)

    // GET SEP-31 transaction should return COMPLETED with no message
    gotSep31TxResponse = sep31Client.getTransaction(postTxResponse.id)
    assertEquals(postTxResponse.id, gotSep31TxResponse.transaction.id)
    assertEquals(COMPLETED.status, gotSep31TxResponse.transaction.status)
    assertNotNull(patchedTx.completedAt)
  }
}

private const val postTxnRequest =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "receiver_id": "MOCK_RECEIVER_ID",
    "sender_id": "MOCK_SENDER_ID",
    "funding_method": "SEPA",
    "fields": {
        "transaction": {
            "receiver_routing_number": "r0123",
            "receiver_account_number": "a0456",
            "type": "SWIFT"
        }
    }
}"""

private const val expectedTxn =
  """
  {
  "transaction": {
    "status": "pending_receiver",
    "amount_in": "10",
    "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "amount_out": "1071.4286",
    "amount_out_asset": "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "fee_details": {
      "total": "1.00",
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    }
  }
}
"""

private const val expectedSep31Info =
  """
  {
    "receive": {
      "JPYC": {
        "enabled": true,
        "quotes_supported": true,
        "quotes_required": false,
        "min_amount": 0,
        "max_amount": 1000000,
        "funding_methods": ["SEPA","SWIFT"]
      },
      "USDC": {
        "enabled": true,
        "quotes_supported": true,
        "quotes_required": false,
        "min_amount": 0,
        "max_amount": 10,
        "funding_methods": ["SEPA","SWIFT"]
      }
    }
  }
  """

private const val patchRequest =
  """
{
  "records": [
    {
      "transaction": {
        "id": "",
        "status": "completed",
        "message": "this is the message",
        "refunds": {
          "amount_refunded": {
            "amount": "1",
            "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
          },
          "amount_fee": {
            "amount": "0.1",
            "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
          },
          "payments": [
            {
              "id": 1,
              "amount": {
                "amount": "0.6",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee": {
                "amount": "0.1",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              }
            },
            {
              "id": 2,
              "amount": {
                "amount": "0.4",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee": {
                "amount": "0",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              }
            }
          ]
        }
      }
    }
  ]
}      
"""

private const val expectedAfterPatch =
  """
  {
  "sep": "31",
  "kind": "receive",
  "status": "completed",
  "amount_expected": {
    "amount": "10",
    "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "amount_in": {
    "amount": "10",
    "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "amount_out": {
    "amount": "1071.4286",
    "asset": "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "fee_details": {
    "total": "1.00",
    "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "message": "this is the message",
  "refunds": {
    "amount_refunded": {
      "amount": "1",
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    },
    "amount_fee": {
      "amount": "0.1",
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    },
    "payments": [
      {
        "id": "1",
        "id_type": "stellar",
        "amount": {
          "amount": "0.6",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "fee": {
          "amount": "0.1",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        }
      },
      {
        "id": "2",
        "id_type": "stellar",
        "amount": {
          "amount": "0.4",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "fee": {
          "amount": "0",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        }
      }
    ]
  },
  "customers": {
    "sender": {
    },
    "receiver": {
    }
  },
  "creator": {
    "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
  }
}
"""
