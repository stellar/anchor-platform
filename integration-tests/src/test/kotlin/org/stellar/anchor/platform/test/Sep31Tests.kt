package org.stellar.anchor.platform.test

import java.time.Instant
import kotlin.streams.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.JSONCompareMode.LENIENT
import org.springframework.data.domain.Sort
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_31
import org.stellar.anchor.api.platform.PlatformTransactionData.builder
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.apiclient.TransactionsOrderBy
import org.stellar.anchor.apiclient.TransactionsSeps
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.platform.*
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Sep1Helper.TomlContent

lateinit var savedTxn: Sep31GetTransactionResponse

class Sep31Tests(config: TestConfig, toml: TomlContent, jwt: String) {
  private val sep12Client: Sep12Client
  private val sep31Client: Sep31Client
  private val sep38Client: Sep38Client
  private val platformApiClient: PlatformApiClient

  init {
    println("Performing SEP31 tests...")
    sep12Client = Sep12Client(toml.getString("KYC_SERVER"), jwt)
    sep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), jwt)
    sep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), jwt)

    platformApiClient = PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)
  }
  private fun `test info endpoint`() {
    printRequest("Calling GET /info")
    val info = sep31Client.getInfo()
    JSONAssert.assertEquals(gson.toJson(info), expectedSep31Info, JSONCompareMode.STRICT)
  }

  private fun `test post and get transactions`() {
    val (senderCustomer, receiverCustomer) = mkCustomers()

    val postTxResponse = createTx(senderCustomer, receiverCustomer)

    // GET Sep31 transaction
    savedTxn = sep31Client.getTransaction(postTxResponse.id)
    JSONAssert.assertEquals(expectedTxn, json(savedTxn), LENIENT)
    assertEquals(postTxResponse.id, savedTxn.transaction.id)
    assertEquals(postTxResponse.stellarMemo, savedTxn.transaction.stellarMemo)
    assertEquals(SepTransactionStatus.PENDING_SENDER.status, savedTxn.transaction.status)
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

  private fun createTx(
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
    assertEquals(
      "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
      postTxResponse.stellarAccountId
    )
    return postTxResponse
  }

  private fun `test transactions`() {
    val (senderCustomer, receiverCustomer) = mkCustomers()

    val tx1 = createTx(senderCustomer, receiverCustomer)
    val tx2 = createTx(senderCustomer, receiverCustomer)
    val tx3 = createTx(senderCustomer, receiverCustomer)

    val all = listOf(tx1, tx2, tx3)

    println("Created transactions ${tx1.id} ${tx2.id} ${tx3.id}")

    // Basic test
    val txs = getTransactions()
    assertOrderCorrect(all, txs.records)

    // Order test
    val descTxs =
      getTransactions(
        order = Sort.Direction.DESC,
      )
    assertOrderCorrect(all.reversed(), descTxs.records)

    patchForTest(tx3, tx2)

    // OrderBy test
    val orderByTxs = getTransactions(orderBy = TransactionsOrderBy.TRANSFER_RECEIVED_AT)
    assertOrderCorrect(listOf(tx2, tx3, tx1), orderByTxs.records)

    val orderByDesc =
      getTransactions(
        orderBy = TransactionsOrderBy.TRANSFER_RECEIVED_AT,
        order = Sort.Direction.DESC
      )
    assertOrderCorrect(listOf(tx3, tx2, tx1), orderByDesc.records)

    // Statuses test
    val statusesTxs =
      getTransactions(
        statuses = listOf(SepTransactionStatus.PENDING_SENDER, SepTransactionStatus.REFUNDED),
      )
    assertOrderCorrect(listOf(tx1, tx2), statusesTxs.records)

    // Pagination test
    val pageNull = getTransactions(pageSize = 1)
    val page0 = getTransactions(pageSize = 1, pageNumber = 0)
    assertOrderCorrect(listOf(tx1), pageNull.records)
    assertOrderCorrect(listOf(tx1), page0.records)

    val page2 = getTransactions(pageSize = 1, pageNumber = 2)
    assertOrderCorrect(listOf(tx3), page2.records)
  }

  private fun getTransactions(
    order: Sort.Direction? = null,
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
    assertEquals(txs.size, records.size)

    assertEquals(
      txs.stream().map { it.id }.toList().toString(),
      records.stream().map { it.id }.toList().toString(),
      "Incorrect oder of transactions"
    )
  }

  private fun patchForTest(tx3: Sep31PostTransactionResponse, tx2: Sep31PostTransactionResponse) {
    platformApiClient.patchTransaction(
      PatchTransactionsRequest.builder()
        .records(
          listOf(
            PatchTransactionRequest(
              builder()
                .id(tx3.id)
                .transferReceivedAt(Instant.now())
                .status(SepTransactionStatus.COMPLETED)
                .build()
            ),
            PatchTransactionRequest(
              builder()
                .id(tx2.id)
                .transferReceivedAt(Instant.now().minusSeconds(12345))
                .status(SepTransactionStatus.REFUNDED)
                .build()
            )
          )
        )
        .build()
    )
  }

  private fun testBadAsset() {
    val customer =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    val pr = sep12Client.putCustomer(customer)

    // Post Sep31 transaction.
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.assetCode = "bad-asset-code"
    txnRequest.receiverId = pr!!.id
    assertThrows<SepException> { sep31Client.postTransaction(txnRequest) }
  }

  private fun `test patch, get and compare`() {
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
    assertEquals(SepTransactionStatus.PENDING_SENDER, getTxResponse.status)
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
            .status(SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE)
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
    assertEquals(SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE, patchedTx.status)
    assertEquals(SEP_31, patchedTx.sep)
    assertEquals("The receiving customer clabe_number is invalid!", patchedTx.message)
    assertTrue(patchedTx.updatedAt > patchedTx.startedAt)

    // GET SEP-31 transaction should return PENDING_CUSTOMER_INFO_UPDATE with a message
    var gotSep31TxResponse = sep31Client.getTransaction(postTxResponse.id)
    assertEquals(postTxResponse.id, gotSep31TxResponse.transaction.id)
    assertEquals(
      SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE.status,
      gotSep31TxResponse.transaction.status
    )
    assertEquals(
      "The receiving customer clabe_number is invalid!",
      gotSep31TxResponse.transaction.requiredInfoMessage
    )
    assertNull(gotSep31TxResponse.transaction.completedAt)

    // PUT sep12/customer with the correct clabe_number
    sep12Client.putCustomer(
      Sep12PutCustomerRequest.builder().id(receiverCustomer.id).clabeNumber("5678").build()
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
          builder()
            .id(getTxResponse.id)
            .completedAt(Instant.now())
            .status(SepTransactionStatus.COMPLETED)
            .build()
        )
        .build()
    patchTxResponse =
      platformApiClient.patchTransaction(
        PatchTransactionsRequest.builder().records(listOf(patchTxRequest)).build()
      )
    assertEquals(1, patchTxResponse.records.size)
    patchedTx = patchTxResponse.records[0]
    assertEquals(getTxResponse.id, patchedTx.id)
    assertEquals(SepTransactionStatus.COMPLETED, patchedTx.status)
    assertEquals(SEP_31, patchedTx.sep)
    assertNull(patchedTx.message)
    assertTrue(patchedTx.startedAt < patchedTx.updatedAt)
    assertNotNull(patchedTx.completedAt)

    // GET SEP-31 transaction should return COMPLETED with no message
    gotSep31TxResponse = sep31Client.getTransaction(postTxResponse.id)
    assertEquals(postTxResponse.id, gotSep31TxResponse.transaction.id)
    assertEquals(SepTransactionStatus.COMPLETED.status, gotSep31TxResponse.transaction.status)
    assertNull(gotSep31TxResponse.transaction.requiredInfoMessage)
    assertNotNull(patchedTx.completedAt)
  }

  fun testAll() {
    println("Performing Sep31 tests...")
    `test info endpoint`()
    `test transactions`()
    `test post and get transactions`()
    `test patch, get and compare`()
    `test bad requests`()
    testBadAsset()
  }
}

private const val postTxnRequest =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "receiver_id": "MOCK_RECEIVER_ID",
    "sender_id": "MOCK_SENDER_ID",
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
    "status": "pending_sender",
    "amount_in": "10",
    "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "amount_out": "1071.4286",
    "amount_out_asset": "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "amount_fee": "1.00",
    "amount_fee_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "stellar_account_id": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
    "stellar_memo_type": "hash"
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
        "fee_fixed": 0,
        "fee_percent": 0,
        "sep12": {
          "sender": {
            "types": {
              "sep31-sender": {
                "description": "U.S. citizens limited to sending payments of less than ${'$'}10,000 in value"
              },
              "sep31-large-sender": {
                "description": "U.S. citizens that do not have sending limits"
              },
              "sep31-foreign-sender": {
                "description": "non-U.S. citizens sending payments of less than ${'$'}10,000 in value"
              }
            }
          },
          "receiver": {
            "types": {
              "sep31-receiver": {
                "description": "U.S. citizens receiving JPY"
              },
              "sep31-foreign-receiver": {
                "description": "non-U.S. citizens receiving JPY"
              }
            }
          }
        },
        "fields": {
          "transaction": {
            "receiver_routing_number": {
              "description": "routing number of the destination bank account",
              "optional": false
            },
            "receiver_account_number": {
              "description": "bank account number of the destination",
              "optional": false
            },
            "type": {
              "description": "type of deposit to make",
              "choices": [
                "SEPA",
                "SWIFT"
              ],
              "optional": false
            }
          }
        }
      },
      "USDC": {
        "enabled": true,
        "quotes_supported": true,
        "quotes_required": false,
        "fee_fixed": 0,
        "fee_percent": 0,
        "max_amount": 1000000,
        "sep12": {
          "sender": {
            "types": {
              "sep31-sender": {
                "description": "U.S. citizens limited to sending payments of less than ${'$'}10,000 in value"
              },
              "sep31-large-sender": {
                "description": "U.S. citizens that do not have sending limits"
              },
              "sep31-foreign-sender": {
                "description": "non-U.S. citizens sending payments of less than ${'$'}10,000 in value"
              }
            }
          },
          "receiver": {
            "types": {
              "sep31-receiver": {
                "description": "U.S. citizens receiving USD"
              },
              "sep31-foreign-receiver": {
                "description": "non-U.S. citizens receiving USD"
              }
            }
          }
        },
        "fields": {
          "transaction": {
            "receiver_routing_number": {
              "description": "routing number of the destination bank account",
              "optional": false
            },
            "receiver_account_number": {
              "description": "bank account number of the destination",
              "optional": false
            },
            "type": {
              "description": "type of deposit to make",
              "choices": [
                "SEPA",
                "SWIFT"
              ],
              "optional": false
            }
          }
        }
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
  "amount_fee": {
    "amount": "1.00",
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
    "account": "GCHU3RZAECOKGM2YAJLQIIYB2ZPLMFTTGN5D3XZNX4RDOEERVLXO7HU4"
  }
}
"""
