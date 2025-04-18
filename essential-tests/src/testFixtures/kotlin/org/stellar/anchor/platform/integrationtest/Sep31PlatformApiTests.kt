package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.util.GsonUtils

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Sep31PlatformApiTests : PlatformApiTests() {
  /**
   * 1. pending_receiver -> request_onchain_funds (called by reference server Sep31EventProcessor)
   * 2. pending_sender -> notify_onchain_funds_received
   * 3. pending_receiver -> notify_customer_info_updated
   * 4. pending_receiver -> notify_transaction_error
   * 5. error -> notify_transaction_recovery
   * 6. pending_receiver -> notify_offchain_funds_pending
   * 7. pending_external -> notify_offchain_funds_sent
   * 8. completed
   */
  @Test
  @Order(10)
  fun `SEP-31 complete full with recovery`() {
    `test sep-31 receive flow`(
      SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS,
      SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. pending_receiver -> request_onchain_funds (called by reference server Sep31EventProcessor)
   * 2. pending_sender -> notify_onchain_funds_received
   * 3. pending_receiver -> notify_refund_sent
   * 4. refunded
   */
  @Test
  @Order(20)
  fun `SEP-31 refunded short`() {
    `test sep-31 receive flow`(
      SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_REQUESTS,
      SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_RESPONSES,
    )
  }

  private fun `test sep-31 receive flow`(actionRequests: String, actionResponses: String) {
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

    inject(
      SEP_31_RECEIVE_FLOW_REQUEST,
      RECEIVER_ID_KEY to receiverCustomer.id,
      SENDER_ID_KEY to senderCustomer.id,
    )
    val receiveRequest = gson.fromJson(receiveRequestJson, Sep31PostTransactionRequest::class.java)
    val receiveResponse = sep31Client.postTransaction(receiveRequest)

    repeat(5) {
      if (sep31Client.getTransaction(receiveResponse.id).transaction.status == "pending_sender")
        return@repeat
      Thread.sleep(1000L)
    }

    if (sep31Client.getTransaction(receiveResponse.id).transaction.status != "pending_sender") {
      throw IllegalStateException("Transaction not in pending_sender status after 5 seconds")
    }

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
}

private const val SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_REQUESTS =
  """
[
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
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 2",
      "refund": {
        "id": "123456",
        "amount": {
          "amount": "1",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": "1",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        }
      }
    }
  }
]   
  """

private const val SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_RESPONSES =
  """
        [
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
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 1",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZWQ0NmIwMzAtM2E5NC00M2RkLThkMWYtYWUwMjNhMGI=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
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
              "status": "pending_anchor",
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
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 2",
              "refunds": {
                "amount_refunded": {
                  "amount": "2",
                  "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
                },
                "amount_fee": {
                  "amount": "1",
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
                      "amount": "1",
                      "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
                    }
                  }
                ]
              },
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZWQ0NmIwMzAtM2E5NC00M2RkLThkMWYtYWUwMjNhMGI=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": "3"
          }
        ]
      """

private const val SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS =
  """ 
[
  {
    "id": "1",
    "method": "notify_onchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 1",
      "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
    }
  },
  {
    "id": "2",
    "method": "notify_customer_info_updated",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 3"
    }
  },
  {
    "id": "3",
    "method": "notify_transaction_error",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 4"
    }
  },
  {
    "id": "4",
    "method": "notify_transaction_recovery",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 5"
    }
  },
  {
    "id": "5",
    "method": "notify_offchain_funds_pending",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 6",
      "external_transaction_id": "ext123456789"
    }
  },
  {
    "id": "6",
    "method": "notify_offchain_funds_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 7",
      "external_transaction_id": "ext123456789"
    }
  }
]
  """

private const val SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES =
  """ 
        [
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
              "started_at": "2024-06-25T20:33:17.013738Z",
              "updated_at": "2024-06-25T20:33:18.072040Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 1",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZDA1NjVlYWYtNjVmNy00ZGIzLWJmZWMtZjNiM2EzMDg=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "started_at": "2024-06-25T20:33:17.013738Z",
              "updated_at": "2024-06-25T20:33:20.102730Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZDA1NjVlYWYtNjVmNy00ZGIzLWJmZWMtZjNiM2EzMDg=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
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
              "status": "error",
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
              "started_at": "2024-06-25T20:33:17.013738Z",
              "updated_at": "2024-06-25T20:33:21.141947Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 4",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZDA1NjVlYWYtNjVmNy00ZGIzLWJmZWMtZjNiM2EzMDg=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
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
              "started_at": "2024-06-25T20:33:17.013738Z",
              "updated_at": "2024-06-25T20:33:22.155595Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 5",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZDA1NjVlYWYtNjVmNy00ZGIzLWJmZWMtZjNiM2EzMDg=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "31",
              "kind": "receive",
              "status": "pending_external",
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
              "started_at": "2024-06-25T20:33:17.013738Z",
              "updated_at": "2024-06-25T20:33:23.170709Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 6",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZDA1NjVlYWYtNjVmNy00ZGIzLWJmZWMtZjNiM2EzMDg=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "external_transaction_id": "ext123456789",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": "5"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "31",
              "kind": "receive",
              "status": "completed",
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
              "started_at": "2024-06-25T20:33:17.013738Z",
              "updated_at": "2024-06-25T20:33:24.184182Z",
              "completed_at": "2024-06-25T20:33:24.184180Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 7",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZDA1NjVlYWYtNjVmNy00ZGIzLWJmZWMtZjNiM2EzMDg=",
                  "memo_type": "hash",
                  "payments": [
                    {
                      "id": "%TESTPAYMENT_ID%",
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
              "external_transaction_id": "ext123456789",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": { "id": "%SENDER_ID%" },
                "receiver": { "id": "%RECEIVER_ID%" }
              },
              "creator": {
                "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
              }
            },
            "id": "6"
          }
        ]
      """

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
