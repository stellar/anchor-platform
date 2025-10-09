package org.stellar.anchor.platform.integrationtest

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.hc.core5.http.HttpStatus.SC_OK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.skyscreamer.jsonassert.Customization
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.comparator.CustomComparator
import org.springframework.data.domain.Sort
import org.stellar.anchor.api.platform.TransactionsOrderBy
import org.stellar.anchor.api.platform.TransactionsSeps
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsReceivedRequest
import org.stellar.anchor.api.rpc.method.RequestOffchainFundsRequest
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.rpc.method.RpcMethod.REQUEST_OFFCHAIN_FUNDS
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.client.*
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.asset.IssuedAssetId

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RpcPlatformApiTests : PlatformApiTests() {
  @Test
  @Order(10)
  fun `test sep24 get transaction by api and rpc`() {
    val depositRequest = gson.fromJson(RPC_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId = depositResponse.id

    val rpcTxn = platformApiClient.getTransactionByRpc(txId)
    JSONAssert.assertEquals(
      inject(EXPECTED_GET_TRANSACTION_BY_RPC_RESPONSE, TX_ID_KEY to txId).trimIndent(),
      gson.toJson(rpcTxn),
      CustomComparator(
        JSONCompareMode.STRICT,
        Customization("started_at") { _, _ -> true },
        Customization("updated_at") { _, _ -> true },
      ),
    )

    val apiTxn = platformApiClient.getTransactionByRpc(txId)
    assertEquals(gson.toJson(apiTxn), gson.toJson(rpcTxn))
  }

  @Test
  @Order(20)
  fun `get transactions by rpc`() {
    val depositRequest = gson.fromJson(RPC_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse1 = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId1 = depositResponse1.id
    val depositResponse2 = sep24Client.deposit(depositRequest)
    val txId2 = depositResponse2.id

    val rpcTxns1 =
      platformApiClient.getTransactionsByRpc(
        TransactionsSeps.SEP_24,
        TransactionsOrderBy.CREATED_AT,
        Sort.Direction.DESC,
        null,
        5,
        0,
      )
    // Validate descending order
    assertEquals(txId2, rpcTxns1.records[0].id)
    assertEquals(txId1, rpcTxns1.records[1].id)

    val rpcTxns2 =
      platformApiClient.getTransactionsByRpc(
        TransactionsSeps.SEP_24,
        TransactionsOrderBy.CREATED_AT,
        Sort.Direction.DESC,
        listOf(SepTransactionStatus.COMPLETED),
        5,
        0,
      )
    // txn1 and txn2 are both incomplete
    val rpcTxnIds = rpcTxns2.records.map { txn -> txn.id }
    assertFalse(rpcTxnIds.contains(txId1))
    assertFalse(rpcTxnIds.contains(txId2))
  }

  @Test
  @Order(30)
  fun `send single rpc request`() {
    val depositRequest = gson.fromJson(RPC_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId = depositResponse.id

    val requestOffchainFundsParams =
      gson.fromJson(REQUEST_OFFCHAIN_FUNDS_PARAMS, RequestOffchainFundsRequest::class.java)
    requestOffchainFundsParams.transactionId = txId
    val rpcRequest =
      RpcRequest.builder()
        .method(REQUEST_OFFCHAIN_FUNDS.toString())
        .jsonrpc(JSON_RPC_VERSION)
        .params(requestOffchainFundsParams)
        .id(1)
        .build()
    val response = platformApiClient.sendRpcRequest(listOf(rpcRequest))
    assertEquals(SC_OK, response.code)
    JSONAssert.assertEquals(
      inject(EXPECTED_RPC_RESPONSE, TX_ID_KEY to txId),
      response.body?.string()?.trimIndent(),
      CustomComparator(
        JSONCompareMode.STRICT,
        Customization("[*].result.started_at") { _, _ -> true },
        Customization("[*].result.updated_at") { _, _ -> true },
      ),
    )

    val txResponse = platformApiClient.getTransactionByRpc(txId)
    assertEquals(SepTransactionStatus.PENDING_USR_TRANSFER_START, txResponse.status)
  }

  @Test
  @Order(40)
  fun `send batch of rpc requests`() {
    val depositRequest = gson.fromJson(RPC_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId = depositResponse.id

    val requestOffchainFundsParams =
      gson.fromJson(REQUEST_OFFCHAIN_FUNDS_PARAMS, RequestOffchainFundsRequest::class.java)
    val notifyOffchainFundsReceivedParams =
      gson.fromJson(
        NOTIFY_OFFCHAIN_FUNDS_RECEIVED_PARAMS,
        NotifyOffchainFundsReceivedRequest::class.java,
      )
    requestOffchainFundsParams.transactionId = txId
    notifyOffchainFundsReceivedParams.transactionId = txId
    val rpcRequest1 =
      RpcRequest.builder()
        .id(1)
        .method(REQUEST_OFFCHAIN_FUNDS.toString())
        .jsonrpc(JSON_RPC_VERSION)
        .params(requestOffchainFundsParams)
        .build()
    val rpcRequest2 =
      RpcRequest.builder()
        .id(2)
        .method(RpcMethod.NOTIFY_OFFCHAIN_FUNDS_RECEIVED.toString())
        .jsonrpc(JSON_RPC_VERSION)
        .params(notifyOffchainFundsReceivedParams)
        .build()
    val response = platformApiClient.sendRpcRequest(listOf(rpcRequest1, rpcRequest2))
    assertEquals(SC_OK, response.code)

    JSONAssert.assertEquals(
      inject(EXPECTED_RPC_BATCH_RESPONSE, TX_ID_KEY to txId),
      response.body?.string()?.trimIndent(),
      CustomComparator(
        JSONCompareMode.STRICT,
        Customization("[*].result.transfer_received_at") { _, _ -> true },
        Customization("[*].result.started_at") { _, _ -> true },
        Customization("[*].result.updated_at") { _, _ -> true },
      ),
    )

    val txResponse = platformApiClient.getTransactionByRpc(txId)
    assertEquals(SepTransactionStatus.PENDING_ANCHOR, txResponse.status)
  }
}

// TODO add refund flow test for withdrawal: https://stellarorg.atlassian.net/browse/ANCHOR-694
abstract class PlatformApiTests : PlatformAPITestBase(TestConfig()) {
  companion object {
    val USDC = IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
    val clientWalletAccount: String = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId
  }

  val gson: Gson = GsonUtils.getInstance()

  val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)
  val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token.token)
  val sep6Client = Sep6Client(toml.getString("TRANSFER_SERVER"), token.token)
  val sep24Client = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), token.token)
  val sep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), token.token)
  val sep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), token.token)

  fun `test flow`(txId: String, actionRequests: String, actionResponses: String) {
    val rpcActionRequestsType = object : TypeToken<List<RpcRequest>>() {}.type
    val rpcActionRequests: List<RpcRequest> =
      gson.fromJson(inject(actionRequests, TX_ID_KEY to txId), rpcActionRequestsType)

    val txnResponse = platformApiClient.getTransactionByRpc(txId)
    assertEquals("127.0.0.1", txnResponse.requestClientIpAddress)

    val rpcActionResponses = platformApiClient.sendRpcRequest(rpcActionRequests)

    val expectedResult = inject(actionResponses, TX_ID_KEY to txId)
    val actualResult = rpcActionResponses.body?.string()?.trimIndent()
    JSONAssert.assertEquals(
      expectedResult,
      actualResult,
      CustomComparator(
        JSONCompareMode.LENIENT,
        Customization("[*].result.transfer_received_at") { _, _ -> true },
        Customization("[*].result.started_at") { _, _ -> true },
        Customization("[*].result.updated_at") { _, _ -> true },
        Customization("[*].result.completed_at") { _, _ -> true },
        Customization("[*].result.memo") { _, _ -> true },
        Customization("[*].result.stellar_transactions[*].memo") { _, _ -> true },
      ),
    )
  }
}

const val CUSTOMER_ID_KEY = "CUSTOMER_ID"

private const val RPC_DEPOSIT_REQUEST =
  """{
    "asset_code": "USDC",
    "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
    "lang": "en"
  }"""

private const val REQUEST_OFFCHAIN_FUNDS_PARAMS =
  """{
    "transaction_id": "%TX_ID%",
    "message": "test message",
    "amount_in": {
        "amount": "1",
        "asset": "iso4217:USD"
    },
    "amount_out": {
        "amount": "0.9",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    },
    "fee_details": {
        "total": "0.1",
        "asset": "iso4217:USD"
    },
    "amount_expected": {
        "amount": "1"
    }
  }"""

private const val NOTIFY_OFFCHAIN_FUNDS_RECEIVED_PARAMS =
  """{
    "transaction_id": "%TX_ID%",
    "message": "test message",
    "amount_in": {
        "amount": "1"
    },
    "amount_out": {
        "amount": "0.9"
    },
    "fee_details": {
        "total": "0.1",
        "asset": "iso4217:USD"
    },
    "external_transaction_id": "1"
  }"""

private const val EXPECTED_RPC_RESPONSE =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "amount_expected": {
                "amount": "1",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_in": { "amount": "1", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "0.9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "0.1", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:36:17.651248Z",
              "updated_at": "2024-06-25T20:36:18.683321Z",
              "message": "test message",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "request_client_ip_address": "127.0.0.1",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": 1
          }
        ]
      """

private const val EXPECTED_RPC_BATCH_RESPONSE =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "amount_expected": {
                "amount": "1",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_in": { "amount": "1", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "0.9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "0.1", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:37:50.883071Z",
              "updated_at": "2024-06-25T20:37:51.908872Z",
              "message": "test message",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "request_client_ip_address": "127.0.0.1",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": 1
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "1",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_in": { "amount": "1", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "0.9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "0.1", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:37:50.883071Z",
              "updated_at": "2024-06-25T20:37:52.922103Z",
              "message": "test message",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "1",
              "client_name": "referenceCustodial",
              "request_client_ip_address": "127.0.0.1",              
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": 2
          }
        ]
      """

private const val EXPECTED_GET_TRANSACTION_BY_RPC_RESPONSE =
  """
          {
            "id": "%TX_ID%",
            "sep": "24",
            "kind": "deposit",
            "status": "incomplete",
            "amount_expected": {
              "asset":
"stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            },
            "started_at": "2024-08-07T20:36:18.344467Z",
            "destination_account":
"GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
            "client_name": "referenceCustodial",
            "request_client_ip_address": "127.0.0.1",
            "customers": {
              "sender": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              },
              "receiver": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "creator": {
              "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
            }
          }
          """
