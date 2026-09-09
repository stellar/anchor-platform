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
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_31
import org.stellar.anchor.api.platform.PlatformTransactionData.builder
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerResponse
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.platform.printRequest
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.debug
import org.stellar.anchor.util.MemoHelper
import org.stellar.anchor.util.SepHelper
import org.stellar.anchor.util.StringHelper.json
import org.stellar.sdk.KeyPair

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
  fun `test DIRECT_PAYMENT_SERVER has expected format`() {
    val directPaymentServerUrl = toml.getString("DIRECT_PAYMENT_SERVER")
    assertNotNull(directPaymentServerUrl)
    assertFalse(
      directPaymentServerUrl.endsWith("/"),
      "DIRECT_PAYMENT_SERVER must not end with a '/'"
    )
    // Parse the URI rather than substring-matching it, so a value like "httpsx://example.com",
    // "http://evil.example/?localhost", "https:foo" (no host), or "ftp://localhost" (wrong scheme
    // for the local exemption) isn't mistaken for a compliant/exempted URL.
    val uri = java.net.URI(directPaymentServerUrl)
    assertTrue(
      !uri.host.isNullOrBlank(),
      "DIRECT_PAYMENT_SERVER must be an absolute URI with a non-blank host"
    )
    val isLocalHttpException =
      uri.scheme == "http" && (uri.host == "localhost" || uri.host == "host.docker.internal")
    assertTrue(
      uri.scheme == "https" || isLocalHttpException,
      "DIRECT_PAYMENT_SERVER must use https (http exempted only for localhost/host.docker.internal, for local testing)"
    )
  }

  @Test
  @Order(30)
  fun `test post and get transactions`() {
    val (senderCustomer, receiverCustomer) = mkCustomers()

    val postTxResponse = createTx(senderCustomer, receiverCustomer)

    // GET Sep31 transaction
    val rawTxnJson = fetchRawTransaction(postTxResponse.id)
    savedTxn = gson.fromJson(rawTxnJson, Sep31GetTransactionResponse::class.java)
    JSONAssert.assertEquals(expectedTxn, rawTxnJson, LENIENT)
    assertEquals(postTxResponse.id, savedTxn.transaction.id)
    assertEquals(PENDING_RECEIVER.status, savedTxn.transaction.status)
    assertCompliesWithProtocolSchema(rawTxnJson, savedTxn)
  }

  private fun fetchRawTransaction(txId: String): String {
    return sep31Client.httpGet(
      "${toml.getString("DIRECT_PAYMENT_SERVER")}/transactions/$txId",
      this.token.token
    )!!
  }

  /**
   * Validates the *raw* response body against the SEP-31 GET-transaction schema before any
   * deserialization can drop unknown properties or coerce types -- `rawJson` is the exact string
   * the server returned. `txn` (already deserialized by the caller) is only used for the semantic
   * checks below that a structural schema can't express: `stellar_account_id` /
   * `stellar_memo`+`stellar_memo_type` well-formedness. Gson already guarantees `started_at`/
   * `completed_at` parse as valid date-times on `txn`, since a malformed value would have failed
   * deserialization before this method is even reached.
   */
  private fun assertCompliesWithProtocolSchema(rawJson: String, txn: Sep31GetTransactionResponse) {
    val root = com.google.gson.JsonParser.parseString(rawJson).asJsonObject
    assertTrue(root.has("transaction"), "response body must have a 'transaction' object")
    val transaction = root.getAsJsonObject("transaction")

    assertTrue(transaction.has("id") && !transaction.get("id").isJsonNull, "'id' is required")
    assertTrue(
      transaction.get("id").asJsonPrimitive.isString,
      "'id' must be a string, not a coerced numeric/boolean value"
    )
    assertFalse(transaction.get("id").asString.isBlank(), "'id' must not be blank")

    val validStatuses = SepHelper.sep31Statuses.map { it.status }.toSet()
    assertTrue(
      transaction.has("status") && !transaction.get("status").isJsonNull,
      "'status' is required"
    )
    assertTrue(
      validStatuses.contains(transaction.get("status").asString),
      "'${transaction.get("status").asString}' is not a status defined by the SEP-31 GET-transaction schema"
    )

    assertTrue(
      transaction.has("fee_details") && !transaction.get("fee_details").isJsonNull,
      "'fee_details' is required"
    )
    val feeDetails = transaction.getAsJsonObject("fee_details")
    assertTrue(
      feeDetails.has("total") && feeDetails.get("total").asJsonPrimitive.isString,
      "'fee_details.total' is required and must be a string"
    )
    assertTrue(
      feeDetails.has("asset") && feeDetails.get("asset").asJsonPrimitive.isString,
      "'fee_details.asset' is required and must be a string"
    )
    if (feeDetails.has("details") && !feeDetails.get("details").isJsonNull) {
      assertTrue(feeDetails.get("details").isJsonArray, "'fee_details.details' must be an array")
      feeDetails.getAsJsonArray("details").forEach { detail ->
        val detailObj = detail.asJsonObject
        assertTrue(
          detailObj.has("name") && detailObj.get("name").asJsonPrimitive.isString,
          "'fee_details.details[].name' is required and must be a string"
        )
        assertTrue(
          detailObj.has("amount") && detailObj.get("amount").asJsonPrimitive.isString,
          "'fee_details.details[].amount' is required and must be a string"
        )
      }
    }

    val optionalStringFields =
      listOf(
        "status_message",
        "amount_in",
        "amount_in_asset",
        "amount_out",
        "amount_out_asset",
        "amount_fee",
        "amount_fee_asset",
        "quote_id",
        "stellar_account_id",
        "stellar_memo_type",
        "stellar_memo",
        "started_at",
        "updated_at",
        "completed_at",
        "stellar_transaction_id",
        "external_transaction_id",
        "required_info_message",
      )
    for (field in optionalStringFields) {
      if (transaction.has(field) && !transaction.get(field).isJsonNull) {
        assertTrue(
          transaction.get(field).asJsonPrimitive.isString,
          "'$field' must be a string when present"
        )
      }
    }
    if (transaction.has("status_eta") && !transaction.get("status_eta").isJsonNull) {
      assertTrue(
        transaction.get("status_eta").asJsonPrimitive.isNumber,
        "'status_eta' must be a number when present"
      )
    }
    if (transaction.has("refunded") && !transaction.get("refunded").isJsonNull) {
      assertTrue(
        transaction.get("refunded").asJsonPrimitive.isBoolean,
        "'refunded' must be a boolean when present"
      )
    }
    if (transaction.has("refunds") && !transaction.get("refunds").isJsonNull) {
      assertTrue(
        transaction.get("refunds").isJsonObject,
        "'refunds' must be an object when present"
      )
    }
    if (
      transaction.has("required_info_updates") &&
        !transaction.get("required_info_updates").isJsonNull
    ) {
      assertTrue(
        transaction.get("required_info_updates").isJsonObject,
        "'required_info_updates' must be an object when present"
      )
    }

    // Semantic checks a structural schema can't express.
    txn.transaction.stellarAccountId?.let {
      try {
        KeyPair.fromAccountId(it)
      } catch (e: Exception) {
        fail<Unit>("'stellar_account_id' must be a valid Stellar public key", e)
      }
    }
    txn.transaction.stellarMemo?.let {
      try {
        // MemoHelper.makeMemo (not Memo.id's Long overload) supports the full uint64 range SEP-31
        // memo ids can carry, not just what fits in a signed 64-bit Long.
        MemoHelper.makeMemo(it, txn.transaction.stellarMemoType)
      } catch (e: Exception) {
        fail<Unit>(
          "invalid 'stellar_memo' for 'stellar_memo_type' (${txn.transaction.stellarMemoType})",
          e
        )
      }
    }
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
    assertThrows<SepValidationException> { sep31Client.postTransaction(txnRequest) }
  }

  @Test
  fun `test returns 400 when no asset_code is given`() {
    // The asset lookup (and its 400 rejection) happens before any SEP-12 customer check, so a
    // mock sender/receiver id is enough -- no real customer needs to be registered.
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.assetCode = null
    assertThrows<SepValidationException> { sep31Client.postTransaction(txnRequest) }
  }

  @Test
  fun `test returns 400 when no amount is given`() {
    // amount validation happens before any SEP-12 customer check, so a mock sender/receiver id is
    // enough -- no real customer needs to be registered.
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.amount = null
    assertThrows<SepValidationException> { sep31Client.postTransaction(txnRequest) }
  }

  @Test
  fun `test requires a SEP-10 JWT`() {
    val unauthenticatedClient = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), "")
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    assertThrows<SepNotAuthorizedException> { unauthenticatedClient.postTransaction(txnRequest) }
  }

  @Test
  fun `test returns 404 for a non-existent transaction`() {
    assertThrows<SepNotFoundException> { sep31Client.getTransaction("not-an-id") }
  }

  @Test
  fun `test quotes_required rejects a transaction with no quote_id`() {
    // preValidateQuote's quote_id check runs before any SEP-12 customer check, so mock
    // sender/receiver ids are enough -- no real customer needs to be registered.
    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.assetCode = "SRT"
    txnRequest.assetIssuer = srtAssetIssuer
    txnRequest.quoteId = null
    assertThrows<SepValidationException> { sep31Client.postTransaction(txnRequest) }
  }

  @Test
  fun `test quotes_required can create and fetch a transaction with a quote`() {
    val (senderCustomer, receiverCustomer) = mkCustomers()
    val quote = sep38Client.postQuote("stellar:SRT:$srtAssetIssuer", "10", "iso4217:USD")

    val txnRequest = gson.fromJson(postTxnRequest, Sep31PostTransactionRequest::class.java)
    txnRequest.assetCode = "SRT"
    txnRequest.assetIssuer = srtAssetIssuer
    txnRequest.senderId = senderCustomer.id
    txnRequest.receiverId = receiverCustomer.id
    txnRequest.quoteId = quote.id
    val postTxResponse = sep31Client.postTransaction(txnRequest)
    assertNotNull(postTxResponse.id)

    val rawTxnJson = fetchRawTransaction(postTxResponse.id)
    val fetchedTxn = gson.fromJson(rawTxnJson, Sep31GetTransactionResponse::class.java)
    assertEquals(postTxResponse.id, fetchedTxn.transaction.id)
    assertEquals(PENDING_RECEIVER.status, fetchedTxn.transaction.status)
    assertCompliesWithProtocolSchema(rawTxnJson, fetchedTxn)
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
}

private const val srtAssetIssuer = "GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B"

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
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      "details": [
        {
          "name": "Sell fee",
          "description": "Fee related to selling the asset.",
          "amount": "1.00"
        }
      ]
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
        "funding_methods": ["SEPA","SWIFT"],
        "fields": {
          "transaction": {
            "receiver_account_number": {
              "description": "Bank account number of the receiver.",
              "optional": true
            }
          }
        }
      },
      "SRT": {
        "enabled": true,
        "quotes_supported": true,
        "quotes_required": true,
        "min_amount": 0,
        "max_amount": 1000000,
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
