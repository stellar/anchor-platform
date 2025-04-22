package org.stellar.anchor.platform.extendedtest.custody

import com.google.gson.reflect.TypeToken
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.Customization
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.comparator.CustomComparator
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.integrationtest.PlatformAPITestBase
import org.stellar.anchor.platform.integrationtest.inject
import org.stellar.anchor.util.GsonUtils

class PlatformApiCustodyTests : PlatformAPITestBase(TestConfig("custody")) {
  companion object {
    private val custodyMockServer = MockWebServer()

    @BeforeAll
    @JvmStatic
    fun construct() {
      custodyMockServer.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            if (
              "POST" == request.method &&
                "//v1/vault/accounts/1/XLM_USDC_T_CEKS/addresses" == request.path
            ) {
              return MockResponse().setResponseCode(200).setBody(CUSTODY_DEPOSIT_ADDRESS_RESPONSE)
            }
            if ("POST" == request.method && "//v1/transactions" == request.path) {
              return MockResponse()
                .setResponseCode(200)
                .setBody(CUSTODY_TRANSACTION_PAYMENT_RESPONSE)
            }
            return MockResponse().setResponseCode(404)
          }
        }

      custodyMockServer.start(58086)
    }
  }

  private val gson = GsonUtils.getInstance()
  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)
  private val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token.token)
  private val sep24Client = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), token.token)
  private val sep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), token.token)

  /**
   * 1. incomplete -> notify_interactive_flow_complete
   * 2. pending_anchor -> request_offchain_funds
   * 3. pending_user_transfer_start -> notify_offchain_funds_received
   * 4. pending_anchor -> do_stellar_payment
   * 5. completed
   */
  @Test
  fun `SEP-24 deposit complete full`() {
    `test deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_FULL_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_FULL_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> notify_interactive_flow_complete
   * 2. pending_anchor -> request_onchain_funds
   * 3. pending_user_transfer_start -> notify_onchain_funds_received
   * 4. pending_anchor -> do_stellar_refund
   * 5. pending_stellar -> notify_refund_sent
   * 6. refunded
   */
  @Test
  fun `SEP-24 withdraw full refund`() {
    `test withdraw flow`(
      SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. pending_receiver -> request_onchain_funds
   * 2. pending_sender -> notify_onchain_funds_received
   * 3. pending_receiver -> do_stellar_refund
   * 4. pending_stellar -> notify_refund_sent
   * 5. pending_sender
   */
  @Test
  fun `SEP-31 refunded do_stellar_refund`() {
    `test receive flow`(
      SEP_31_RECEIVE_REFUNDED_DO_STELLAR_REFUND_FLOW_ACTION_REQUESTS,
      SEP_31_RECEIVE_REFUNDED_DO_STELLAR_REFUND_FLOW_ACTION_RESPONSES,
    )
  }

  @Suppress("UNCHECKED_CAST")
  private fun `test deposit flow`(actionRequests: String, actionResponse: String) {
    val depositRequest = gson.fromJson(SEP_24_DEPOSIT_FLOW_REQUEST, HashMap::class.java)
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    `test flow`(depositResponse.id, actionRequests, actionResponse)
  }

  private fun `test receive flow`(actionRequests: String, actionResponses: String) {
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(CUSTOMER_1, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = sep12Client.putCustomer(receiverCustomerRequest)
    val senderCustomerRequest =
      GsonUtils.getInstance().fromJson(CUSTOMER_2, Sep12PutCustomerRequest::class.java)
    val senderCustomer = sep12Client.putCustomer(senderCustomerRequest)

    val receiveRequestJson =
      inject(
        SEP_31_RECEIVE_FLOW_REQUEST,
        RECEIVER_ID_KEY to receiverCustomer!!.id,
        SENDER_ID_KEY to senderCustomer!!.id,
      )
    val receiveRequest = gson.fromJson(receiveRequestJson, Sep31PostTransactionRequest::class.java)
    val receiveResponse = sep31Client.postTransaction(receiveRequest)

    val updatedActionRequests =
      inject(
        actionRequests,
        RECEIVER_ID_KEY to receiverCustomer.id,
        SENDER_ID_KEY to senderCustomer.id,
      )
    val updatedActionResponses =
      inject(
        actionResponses,
        RECEIVER_ID_KEY to receiverCustomer.id,
        SENDER_ID_KEY to senderCustomer.id,
      )

    `test flow`(receiveResponse.id, updatedActionRequests, updatedActionResponses)
  }

  @Suppress("UNCHECKED_CAST")
  private fun `test withdraw flow`(actionRequests: String, actionResponse: String) {
    val withdrawRequest = gson.fromJson(SEP_24_WITHDRAW_FLOW_REQUEST, HashMap::class.java)
    val withdrawResponse = sep24Client.withdraw(withdrawRequest as HashMap<String, String>)
    `test flow`(withdrawResponse.id, actionRequests, actionResponse)
  }

  private fun `test flow`(txId: String, actionRequests: String, actionResponses: String) {
    val rpcActionRequestsType = object : TypeToken<List<RpcRequest>>() {}.type
    val rpcActionRequests: List<RpcRequest> =
      gson.fromJson(inject(actionRequests, TX_ID_KEY to txId), rpcActionRequestsType)

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
      ),
    )
  }
}

private const val SEP_24_DEPOSIT_COMPLETE_FULL_FLOW_ACTION_REQUESTS =
  """
[
  {
    "id": "1",
    "method": "notify_interactive_flow_completed",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 1",
      "amount_in": {
        "amount": "100",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": {
        "total": "5",
        "asset": "iso4217:USD"
      },
      "amount_expected": {
        "amount": "3"
      }
    }
  },
  {
    "id": "2",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 2",
      "amount_in": {
        "amount": "100",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": {
        "total": "5",
        "asset": "iso4217:USD"
      },
      "amount_expected": {
        "amount": "100"
      }
    }
  },
  {
    "id": "3",
    "method": "notify_offchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 3",
      "funds_received_at": "2023-07-04T12:34:56Z",
      "external_transaction_id": "ext-123456",
      "amount_in": {
        "amount": 1
      },
      "amount_out": {
        "amount": 1
      },
      "fee_details": {
        "total": 0,
        "asset": "iso4217:USD"
      }
    }
  },
  {
    "id": "4",
    "method": "do_stellar_payment",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 4"
    }
  }
]
"""

private const val SEP_24_DEPOSIT_COMPLETE_FULL_FLOW_ACTION_RESPONSES =
  """
[
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "pending_anchor",
      "amount_expected": {
        "amount": "3",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "amount_in": {
        "amount": "100",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": {
        "total": "5",
        "asset": "iso4217:USD"
      },
      "started_at": "2023-08-07T12:52:01.663006Z",
      "updated_at": "2023-08-07T12:52:03.100242Z",
      "message": "test message 1",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "client_name": "referenceCustodial"
    },
    "id": "1"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "pending_user_transfer_start",
      "amount_expected": {
        "amount": "100",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "amount_in": {
        "amount": "100",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": {
        "total": "5",
        "asset": "iso4217:USD"
      },
      "started_at": "2023-08-07T12:52:01.663006Z",
      "updated_at": "2023-08-07T12:52:04.165625Z",
      "message": "test message 2",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "client_name": "referenceCustodial"
    },
    "id": "2"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "pending_anchor",
      "amount_expected": {
        "amount": "100",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "amount_in": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": {
        "total": "0",
        "asset": "iso4217:USD"
      },
      "started_at": "2023-08-07T12:52:01.663006Z",
      "updated_at": "2023-08-07T12:52:05.241766Z",
      "message": "test message 3",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "external_transaction_id": "ext-123456",
      "client_name": "referenceCustodial"
    },
    "id": "3"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "pending_stellar",
      "amount_expected": {
        "amount": "100",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "amount_in": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": {
        "total": "0",
        "asset": "iso4217:USD"
      },
      "started_at": "2023-08-07T12:52:01.663006Z",
      "updated_at": "2023-08-07T12:52:07.016199Z",
      "message": "test message 4",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "external_transaction_id": "ext-123456",
      "client_name": "referenceCustodial"
    },
    "id": "4"
  }
]
"""

private const val SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS =
  """
[
  {
    "id": "1",
    "method": "notify_interactive_flow_completed",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 1",
      "amount_in": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "2",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_expected": {
        "amount": "3"
      }
    }
  },
  {
    "id": "2",
    "method": "request_onchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 2",
      "amount_in": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "0",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_expected": {
        "amount": "3"
      }
    }
  },
  {
    "id": "3",
    "method": "notify_onchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 3",
      "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%",
      "amount_in": {
        "amount": "1"
      }
    }
  },
  {
    "id": "4",
    "method": "do_stellar_refund",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 4",
      "refund": {
        "id": "123456",
        "amount": {
          "amount": "1",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": "0",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        }
      }
    }
  },
  {
    "id": "5",
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 5",
      "refund": {
        "id": "123456",
        "amount": {
          "amount": "1",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": "0",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        }
      }
    }
  }
]
"""

private const val SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES =
  """
[
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "withdrawal",
      "status": "pending_anchor",
      "amount_expected": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "2",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2023-08-07T10:35:38.513616Z",
      "updated_at": "2023-08-07T10:35:39.973638Z",
      "message": "test message 1",
      "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
      "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
      "client_name": "referenceCustodial"
    },
    "id": "1"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "withdrawal",
      "status": "pending_user_transfer_start",
      "amount_expected": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "0",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2023-08-07T10:35:38.513616Z",
      "updated_at": "2023-08-07T10:35:41.086448Z",
      "message": "test message 2",
      "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
      "destination_account": "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O",
      "memo": "testMemo",
      "memo_type": "id",
      "client_name": "referenceCustodial"
    },
    "id": "2"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "withdrawal",
      "status": "pending_anchor",
      "amount_expected": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "0",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2023-08-07T10:35:38.513616Z",
      "updated_at": "2023-08-07T10:35:42.792987Z",
      "message": "test message 3",
      "stellar_transactions": [
        {
          "id": "%TESTPAYMENT_TXN_HASH%",
          "memo": "testMemo",
          "memo_type": "id",
          "payments": [
            {
              "amount": {
                "amount": "%TESTPAYMENT_AMOUNT%",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
              "destination_account": "%TESTPAYMENT_DEST_ACCOUNT%"
            }
          ]
        }
      ],
      "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
      "destination_account": "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O",
      "memo": "testMemo",
      "memo_type": "id",
      "client_name": "referenceCustodial"
    },
    "id": "3"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "withdrawal",
      "status": "pending_stellar",
      "amount_expected": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "0",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2023-08-07T10:35:38.513616Z",
      "updated_at": "2023-08-07T10:35:44.019962Z",
      "message": "test message 4",
      "stellar_transactions": [
        {
          "id": "%TESTPAYMENT_TXN_HASH%",
          "memo": "testMemo",
          "memo_type": "id",
          "payments": [
            {
              "amount": {
                "amount": "%TESTPAYMENT_AMOUNT%",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
              "destination_account": "%TESTPAYMENT_DEST_ACCOUNT%"
            }
          ]
        }
      ],
      "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
      "destination_account": "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O",
      "memo": "testMemo",
      "memo_type": "id",
      "client_name": "referenceCustodial"
    },
    "id": "4"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "withdrawal",
      "status": "refunded",
      "amount_expected": {
        "amount": "3",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "1",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "0",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2023-08-07T10:35:38.513616Z",
      "updated_at": "2023-08-07T10:35:52.647527Z",
      "completed_at": "2023-08-07T10:35:52.647537Z",
      "message": "test message 5",
      "refunds": {
        "amount_refunded": {
          "amount": "1",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": "0",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "payments": [
          {
            "id": "123456",
            "id_type": "stellar",
            "amount": {
              "amount": "1",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            },
            "fee": {
              "amount": "0",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            }
          }
        ]
      },
      "stellar_transactions": [
        {
          "id": "%TESTPAYMENT_TXN_HASH%",
          "memo": "testMemo",
          "memo_type": "id",
          "payments": [
            {
              "amount": {
                "amount": "%TESTPAYMENT_AMOUNT%",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
              "destination_account": "%TESTPAYMENT_DEST_ACCOUNT%"
            }
          ]
        }
      ],
      "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
      "destination_account": "GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O",
      "memo": "testMemo",
      "memo_type": "id",
      "client_name": "referenceCustodial"
    },
    "id": "5"
  }
]
"""

private const val SEP_31_RECEIVE_REFUNDED_DO_STELLAR_REFUND_FLOW_ACTION_REQUESTS =
  """
    [
      {
        "id": "1",
        "method": "request_onchain_funds",
        "jsonrpc": "2.0",
        "params": {
          "transaction_id": "%TX_ID%"
        }
      },
      {
        "id": "2",
        "method": "notify_onchain_funds_received",
        "jsonrpc": "2.0",
        "params": {
          "transaction_id": "%TX_ID%",
          "message": "test message 1",
          "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
        }
      },
      {
        "id": "3",
        "method": "do_stellar_refund",
        "jsonrpc": "2.0",
        "params": {
          "transaction_id": "%TX_ID%",
          "message": "test message 2",
          "refund": {
            "id": "123456",
            "amount": {
              "amount": "5",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            },
            "amount_fee": {
              "amount": "5",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            }
          }
        }
      },
      {
        "id": "4",
        "method": "notify_refund_sent",
        "jsonrpc": "2.0",
        "params": {
          "transaction_id": "%TX_ID%",
          "message": "test message 3",
          "refund": {
            "id": "123456",
            "amount": {
              "amount": "5",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            },
            "amount_fee": {
              "amount": "5",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            }
          }
        }
      }
    ]
  """

private const val SEP_31_RECEIVE_REFUNDED_DO_STELLAR_REFUND_FLOW_ACTION_RESPONSES =
  """
[
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "31",
      "kind": "receive",
      "status": "pending_sender",
      "amount_expected": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {},
      "fee_details": {
        "total": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2024-08-20T14:51:13.884362Z",
      "updated_at": "2024-08-20T14:51:14.998779Z",
      "destination_account": "%CUSTODY_DEST_ACCOUNT%",
      "memo": "testMemo",
      "memo_type": "id",
      "refund_memo": "testMemo",
      "refund_memo_type": "id",
      "client_name": "referenceCustodial",
      "customers": {
        "sender": { "id": "%SENDER_ID%" },
        "receiver": { "id": "%RECEIVER_ID%" }
      },
      "creator": {
        "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
      }
    },
    "id": "1"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "31",
      "kind": "receive",
      "status": "pending_receiver",
      "amount_expected": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {},
      "fee_details": {
        "total": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2024-08-20T14:51:13.884362Z",
      "updated_at": "2024-08-20T14:51:16.193778Z",
      "transfer_received_at": "2024-10-01T00:34:38Z",
      "message": "test message 1",
      "stellar_transactions": [
        {
          "id": "%TESTPAYMENT_TXN_HASH%",
          "memo": "testMemo",
          "memo_type": "id",
          "payments": [
            {
              "amount": {
                "amount": "%TESTPAYMENT_AMOUNT%",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
              "destination_account": "%TESTPAYMENT_DEST_ACCOUNT%"
            }
          ]
        }
      ],
      "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
      "destination_account": "%CUSTODY_DEST_ACCOUNT%",
      "memo": "testMemo",
      "memo_type": "id",
      "refund_memo": "testMemo",
      "refund_memo_type": "id",
      "client_name": "referenceCustodial",
      "customers": {
        "sender": {
          "id": "%SENDER_ID%"
        },
        "receiver": {
          "id": "%RECEIVER_ID%"
        }
      },
      "creator": {
        "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
      }
    },
    "id": "2"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "31",
      "kind": "receive",
      "status": "pending_stellar",
      "amount_expected": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {},
      "fee_details": {
        "total": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2024-08-20T14:51:13.884362Z",
      "updated_at": "2024-08-20T14:51:17.301305Z",
      "transfer_received_at": "2024-10-01T00:34:38Z",
      "message": "test message 2",
      "stellar_transactions": [
        {
          "id": "%TESTPAYMENT_TXN_HASH%",
          "memo": "testMemo",
          "memo_type": "id",
          "payments": [
            {
              "amount": {
                "amount": "%TESTPAYMENT_AMOUNT%",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
              "destination_account": "%TESTPAYMENT_DEST_ACCOUNT%"
            }
          ]
        }
      ],
      "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
      "destination_account": "%CUSTODY_DEST_ACCOUNT%",
      "memo": "testMemo",
      "memo_type": "id",
      "refund_memo": "testMemo",
      "refund_memo_type": "id",
      "client_name": "referenceCustodial",
      "customers": {
        "sender": {
          "id": "%SENDER_ID%"
        },
        "receiver": {
          "id": "%RECEIVER_ID%"
        }
      },
      "creator": {
        "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
      }
    },
    "id": "3"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "31",
      "kind": "receive",
      "status": "refunded",
      "amount_expected": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_in": {
        "amount": "10",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {},
      "fee_details": {
        "total": "1",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "started_at": "2024-08-20T14:51:13.884362Z",
      "updated_at": "2024-08-20T14:51:18.325903Z",
      "completed_at": "2024-08-20T14:51:18.325901Z",
      "transfer_received_at": "2024-10-01T00:34:38Z",
      "message": "test message 3",
      "refunds": {
        "amount_refunded": {
          "amount": "10",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": "5",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "payments": [
          {
            "id": "123456",
            "id_type": "stellar",
            "amount": {
              "amount": "5",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            },
            "fee": {
              "amount": "5",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            }
          }
        ]
      },
      "stellar_transactions": [
        {
          "id": "%TESTPAYMENT_TXN_HASH%",
          "memo": "testMemo",
          "memo_type": "id",
          "payments": [
            {
              "amount": {
                "amount": "%TESTPAYMENT_AMOUNT%",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
              "destination_account": "%TESTPAYMENT_DEST_ACCOUNT%"
            }
          ]
        }
      ],
      "source_account": "%TESTPAYMENT_SRC_ACCOUNT%",
      "destination_account": "%CUSTODY_DEST_ACCOUNT%",
      "memo": "testMemo",
      "memo_type": "id",
      "refund_memo": "testMemo",
      "refund_memo_type": "id",
      "client_name": "referenceCustodial",
      "customers": {
        "sender": {
          "id": "%SENDER_ID%"
        },
        "receiver": {
          "id": "%RECEIVER_ID%"
        }
      },
      "creator": {
        "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
      }
    },
    "id": "4"
  }
]
  """

private const val SEP_24_DEPOSIT_FLOW_REQUEST = """
{
  "asset_code": "USDC"
}
"""

private const val SEP_24_WITHDRAW_FLOW_REQUEST =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
    "account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
    "lang": "en"
}"""

private const val SEP_31_RECEIVE_FLOW_REQUEST =
  """
{
  "amount": "10",
  "asset_code": "USDC",
  "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
  "receiver_id": "%RECEIVER_ID%",
  "sender_id": "%SENDER_ID%",
  "funding_method": "SEPA",
  "fields": {
    "transaction": {
      "receiver_routing_number": "r0123",
      "receiver_account_number": "a0456",
      "type": "SWIFT"
    }
  }
}
"""

private const val CUSTOMER_1 =
  """
{
  "first_name": "John",
  "last_name": "Doe",
  "email_address": "johndoe@test.com",
  "address": "123 Washington Street",
  "city": "San Francisco",
  "state_or_province": "CA",
  "address_country_code": "US",
  "clabe_number": "1234",
  "bank_number": "abcd",
  "bank_account_number": "1234",
  "bank_account_type": "checking"
}
"""

private const val CUSTOMER_2 =
  """
{
  "first_name": "Jane",
  "last_name": "Doe",
  "email_address": "janedoe@test.com",
  "address": "321 Washington Street",
  "city": "San Francisco",
  "state_or_province": "CA",
  "address_country_code": "US",
  "clabe_number": "5678",
  "bank_number": "efgh",
  "bank_account_number": "5678",
  "bank_account_type": "checking"
}
"""

private const val CUSTODY_TRANSACTION_PAYMENT_RESPONSE =
  """
{
  "id":"df0442b4-6d53-44cd-82d7-3c48edc0b1ac",
  "status": "SUBMITTED"
}
"""

private const val CUSTODY_DEPOSIT_ADDRESS_RESPONSE =
  """
{
  "address":"GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O",
  "legacyAddress": "testLegacyAddress",
  "enterpriseAddress": "testEnterpriseAddress",
  "tag":"testMemo",
  "bip44AddressIndex":12345
}
"""
