package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Sep6PlatformApiTests : PlatformApiTests() {

  /**
   * 1. incomplete -> notify_customer_info_updated
   * 2. pending_customer_info_update -> request_offchain_funds
   * 3. pending_user_transfer_start -> notify_offchain_funds_received
   * 4. pending_anchor -> request_trust
   * 5. pending_trust -> notify_trust_set
   * 6. pending_anchor -> notify_onchain_funds_sent
   * 7. completed
   */
  @Test
  @Order(10)
  fun `SEP-6 deposit complete full with trust flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_transaction_error
   * 4. error -> notify_transaction_recovery
   * 5. pending_anchor -> notify_onchain_funds_sent
   * 6. completed
   */
  @Test
  @Order(20)
  fun `SEP-6 deposit complete full with recovery flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_onchain_funds_sent
   * 4. completed
   */
  @Test
  @Order(30)
  fun `SEP-6 deposit complete short flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_refund_pending
   * 4. pending_external -> notify_refund_sent
   * 5. pending_anchor -> notify_onchain_funds_sent
   * 6. completed
   */
  @Test
  @Order(40)
  fun `SEP-6 deposit complete short partial refund flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_sent
   * 3. pending_user_transfer_start -> notify_offchain_funds_received
   * 4. pending_anchor -> notify_onchain_funds_sent
   * 5. completed
   */
  @Test
  @Order(50)
  fun `SEP-6 deposit with pending-external status`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_WITH_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_WITH_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_onchain_funds_sent
   * 4. completed
   */
  @Test
  @Order(60)
  fun `SEP-6 deposit-exchange complete short flow`() {
    `test sep6 deposit-exchange flow`(
      SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_sent
   * 4. completed
   */
  @Test
  @Order(70)
  fun `SEP-6 withdraw complete short flow`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_pending
   * 4. pending_external -> notify_offchain_funds_sent
   * 5. completed
   */
  @Test
  @Order(80)
  fun `SEP-6 withdraw complete full via pending external`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_available
   * 4. pending_user_transfer_complete -> notify_offchain_funds_sent
   * 5. completed
   */
  @Test
  @Order(90)
  fun `SEP-6 withdraw complete full via pending user`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_sent
   * 4. completed
   */
  @Test
  @Order(100)
  fun `SEP-6 withdraw-exchange complete short flow`() {
    `test sep6 withdraw-exchange flow`(
      SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES,
    )
  }

  @Test
  @Order(110)
  fun `SEP-6 withdraw full refund`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES,
    )
  }

  private fun `test sep6 withdraw flow`(actionRequests: String, actionResponse: String) {
    val withdrawRequest = gson.fromJson(SEP_6_WITHDRAW_FLOW_REQUEST, HashMap::class.java)

    val customer =
      sep12Client.putCustomer(
        Sep12PutCustomerRequest.builder().account(clientWalletAccount).build()
      )
    val updatedActionRequests = actionRequests.replace(CUSTOMER_ID_KEY, customer!!.id)

    @Suppress("UNCHECKED_CAST")
    val withdrawResponse = sep6Client.withdraw(withdrawRequest as HashMap<String, String>)
    `test flow`(withdrawResponse.id, updatedActionRequests, actionResponse)
  }

  private fun `test sep6 withdraw-exchange flow`(actionRequests: String, actionResponse: String) {
    val withdrawRequest = gson.fromJson(SEP_6_WITHDRAW_EXCHANGE_FLOW_REQUEST, HashMap::class.java)

    val customer =
      sep12Client.putCustomer(
        Sep12PutCustomerRequest.builder().account(clientWalletAccount).build()
      )
    val updatedActionRequests = actionRequests.replace(CUSTOMER_ID_KEY, customer!!.id)

    @Suppress("UNCHECKED_CAST")
    val withdrawResponse =
      sep6Client.withdraw(withdrawRequest as HashMap<String, String>, exchange = true)
    `test flow`(withdrawResponse.id, updatedActionRequests, actionResponse)
  }

  private fun `test sep6 deposit flow`(actionRequests: String, actionResponse: String) {
    val depositRequest = gson.fromJson(SEP_6_DEPOSIT_FLOW_REQUEST, HashMap::class.java)

    val customer =
      sep12Client.putCustomer(
        Sep12PutCustomerRequest.builder().account(clientWalletAccount).build()
      )
    val updatedActionRequests = actionRequests.replace(CUSTOMER_ID_KEY, customer!!.id)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep6Client.deposit(depositRequest as HashMap<String, String>)
    `test flow`(depositResponse.id, updatedActionRequests, actionResponse)
  }

  private fun `test sep6 deposit-exchange flow`(actionRequests: String, actionResponse: String) {
    val depositRequest = gson.fromJson(SEP_6_DEPOSIT_EXCHANGE_FLOW_REQUEST, HashMap::class.java)

    val customer =
      sep12Client.putCustomer(
        Sep12PutCustomerRequest.builder().account(clientWalletAccount).build()
      )
    val updatedActionRequests = actionRequests.replace(CUSTOMER_ID_KEY, customer!!.id)

    @Suppress("UNCHECKED_CAST")
    val depositResponse =
      sep6Client.deposit(depositRequest as HashMap<String, String>, exchange = true)
    `test flow`(depositResponse.id, updatedActionRequests, actionResponse)
  }
}

private const val SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_offchain_funds",
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
                "amount": "100"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_offchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "funds_received_at": "2023-07-04T12:34:56Z",
              "external_transaction_id": "ext-123456",
              "amount_in": {
                "amount": "100"
              }
            }
          },
          {
            "id": "3",
            "method": "notify_onchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          }
        ]
      """

private val SEP_6_DEPOSIT_WITH_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS =
  """
  [
          {
            "id": "1",
            "method": "request_offchain_funds",
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
                "amount": "100"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_offchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "funds_sent_at": "2023-07-04T12:34:56Z",
              "external_transaction_id": "ext-123456"
            }
          },
          {
            "id": "3",
            "method": "notify_offchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "funds_received_at": "2023-07-04T12:34:56Z",
              "external_transaction_id": "ext-123456",
              "amount_in": {
                "amount": "100"
              }
            }
          },
          {
            "id": "4",
            "method": "notify_onchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          }
        ]

  """
    .trimIndent()

private const val SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:02:31.003419Z",
              "updated_at": "2024-06-25T20:02:32.055853Z",
              "message": "test message 1",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:02:31.003419Z",
              "updated_at": "2024-06-25T20:02:33.085143Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "completed",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:02:31.003419Z",
              "updated_at": "2024-06-25T20:02:34.180861Z",
              "completed_at": "2024-06-25T20:02:34.180858Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          }
        ]
      """

private val SEP_6_DEPOSIT_WITH_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:02:31.003419Z",
              "updated_at": "2024-06-25T20:02:32.055853Z",
              "message": "test message 1",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_external",
              "type": "SWIFT"
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:02:31.003419Z",
              "updated_at": "2024-06-25T20:02:33.085143Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "completed",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:02:31.003419Z",
              "updated_at": "2024-06-25T20:02:34.180861Z",
              "completed_at": "2024-06-25T20:02:34.180858Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "4"
          }
        ]
  """
    .trimIndent()

private const val SEP_6_DEPOSIT_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit-exchange",
              "status": "pending_user_transfer_start",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:05:21.747241Z",
              "updated_at": "2024-06-25T20:05:22.776951Z",
              "message": "test message 1",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit-exchange",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:05:21.747241Z",
              "updated_at": "2024-06-25T20:05:23.796201Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit-exchange",
              "status": "completed",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:05:21.747241Z",
              "updated_at": "2024-06-25T20:05:24.856353Z",
              "completed_at": "2024-06-25T20:05:24.856350Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          }
        ]
      """

private const val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "notify_customer_info_updated",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "customer_id": "CUSTOMER_ID",
              "customer_type": "sep6"
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
                "amount": "10.11",
                "asset": "iso4217:USD"
              },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": {
                "total": "1.11",
                "asset": "iso4217:USD"
              },
              "amount_expected": {
                "amount": "10.11"
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
                "amount": "10.11"
              },
              "amount_out": {
                "amount": "9"
              },
              "fee_details": {
                "total": "1.11",
                "asset": "iso4217:USD"
              },
              "amount_expected": {
                "amount": "10.11"
              }
            }
          },
          {
            "id": "4",
            "method": "request_trust",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 4"
            }
          },
          {
            "id": "5",
            "method": "notify_trust_set",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 5"
            }
          },
          {
            "id": "6",
            "method": "notify_onchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 6",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          }
        ]
      """

private const val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_customer_info_update",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "1",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:07:15.112397Z",
              "updated_at": "2024-06-25T20:07:16.135912Z",
              "message": "test message 1",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:07:15.112397Z",
              "updated_at": "2024-06-25T20:07:16.200042Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:07:15.112397Z",
              "updated_at": "2024-06-25T20:07:17.224307Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 3",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_trust",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:07:15.112397Z",
              "updated_at": "2024-06-25T20:07:18.259066Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 4",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:07:15.112397Z",
              "updated_at": "2024-06-25T20:07:19.304664Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 5",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "5"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "completed",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:07:15.112397Z",
              "updated_at": "2024-06-25T20:07:20.375086Z",
              "completed_at": "2024-06-25T20:07:20.375084Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 6",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "6"
          }
        ]
      """

private const val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_offchain_funds",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "amount_in": {
                "amount": "10.11",
                "asset": "iso4217:USD"
              },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": {
                "total": "1.11",
                "asset": "iso4217:USD"
              },
              "amount_expected": {
                "amount": "10.11"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_offchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "funds_received_at": "2023-07-04T12:34:56Z",
              "external_transaction_id": "ext-123456",
              "amount_in": {
                "amount": "10.11"
              },
              "amount_out": {
                "amount": "9"
              },
              "fee_details": {
                "total": "1.11",
                "asset": "iso4217:USD"
              },
              "amount_expected": {
                "amount": "10.11"
              }
            }
          },
          {
            "id": "3",
            "method": "notify_transaction_error",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3"
            }
          },
          {
            "id": "4",
            "method": "notify_transaction_recovery",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 4"
            }
          },
          {
            "id": "5",
            "method": "notify_onchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 5",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          }
        ]
      """

private const val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:09:35.672365Z",
              "updated_at": "2024-06-25T20:09:36.699649Z",
              "message": "test message 1",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:09:35.672365Z",
              "updated_at": "2024-06-25T20:09:37.713378Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "error",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:09:35.672365Z",
              "updated_at": "2024-06-25T20:09:38.732764Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 3",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:09:35.672365Z",
              "updated_at": "2024-06-25T20:09:39.766277Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 4",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "completed",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:09:35.672365Z",
              "updated_at": "2024-06-25T20:09:40.830340Z",
              "completed_at": "2024-06-25T20:09:40.830337Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 5",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "5"
          }
        ]
      """

private const val SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_offchain_funds",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "amount_in": {
                "amount": "10.11",
                "asset": "iso4217:USD"
              },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": {
                "total": "1.11",
                "asset": "iso4217:USD"
              },
              "amount_expected": {
                "amount": "10.11"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_offchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "funds_received_at": "2023-07-04T12:34:56Z",
              "external_transaction_id": "ext-123456",
              "amount_in": {
                "amount": "1000.11"
              },
              "amount_out": {
                "amount": "9"
              },
              "fee_details": {
                "total": "1.11",
                "asset": "iso4217:USD"
              },
              "amount_expected": {
                "amount": "10.11"
              }
            }
          },
          {
            "id": "3",
            "method": "notify_refund_pending",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "refund": {
                "id": "123456",
                "amount": {
                  "amount": "989.11",
                  "asset": "iso4217:USD"
                },
                "amount_fee": {
                  "amount": "1",
                  "asset": "iso4217:USD"
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
              "message": "test message 4",
              "refund": {
                "id": "123456",
                "amount": {
                  "amount": "989.11",
                  "asset": "iso4217:USD"
                },
                "amount_fee": {
                  "amount": "1",
                  "asset": "iso4217:USD"
                }
              }
            }
          },
          {
            "id": "5",
            "method": "notify_onchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 5",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          }
        ]
      """

private const val SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_user_transfer_start",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:11:02.407205Z",
              "updated_at": "2024-06-25T20:11:03.439769Z",
              "message": "test message 1",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:11:02.407205Z",
              "updated_at": "2024-06-25T20:11:04.458865Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_external",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:11:02.407205Z",
              "updated_at": "2024-06-25T20:11:05.490779Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 3",
              "refunds": {
                "amount_refunded": { "amount": "990.11", "asset": "iso4217:USD" },
                "amount_fee": { "amount": "1", "asset": "iso4217:USD" },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "external",
                    "amount": { "amount": "989.11", "asset": "iso4217:USD" },
                    "fee": { "amount": "1", "asset": "iso4217:USD" }
                  }
                ]
              },
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "pending_anchor",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:11:02.407205Z",
              "updated_at": "2024-06-25T20:11:06.545060Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 4",
              "refunds": {
                "amount_refunded": { "amount": "990.11", "asset": "iso4217:USD" },
                "amount_fee": { "amount": "1", "asset": "iso4217:USD" },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "external",
                    "amount": { "amount": "989.11", "asset": "iso4217:USD" },
                    "fee": { "amount": "1", "asset": "iso4217:USD" }
                  }
                ]
              },
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "deposit",
              "status": "completed",
              "type": "SWIFT",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:11:02.407205Z",
              "updated_at": "2024-06-25T20:11:07.603229Z",
              "completed_at": "2024-06-25T20:11:07.603226Z",
              "transfer_received_at": "2023-07-04T12:34:56Z",
              "message": "test message 5",
              "refunds": {
                "amount_refunded": { "amount": "990.11", "asset": "iso4217:USD" },
                "amount_fee": { "amount": "1", "asset": "iso4217:USD" },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "external",
                    "amount": { "amount": "989.11", "asset": "iso4217:USD" },
                    "fee": { "amount": "1", "asset": "iso4217:USD" }
                  }
                ]
              },
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "5"
          }
        ]
      """

private const val SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_onchain_funds",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": {
                "amount": "95",
                "asset": "iso4217:USD"
              },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_expected": {
                "amount": "100"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_onchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          },
          {
            "id": "3",
            "method": "notify_offchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "external_transaction_id": "ext-123456"
            }
          }
        ]
      """

private const val SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:12:42.295731Z",
              "updated_at": "2024-06-25T20:12:43.318713Z",
              "message": "test message 1",
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "YWEyNDVlMjgtZGIyYS00YmRjLThkODgtYzExYmJhM2Y=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_anchor",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:12:42.295731Z",
              "updated_at": "2024-06-25T20:12:44.386504Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "YWEyNDVlMjgtZGIyYS00YmRjLThkODgtYzExYmJhM2Y=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "completed",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:12:42.295731Z",
              "updated_at": "2024-06-25T20:12:45.408622Z",
              "completed_at": "2024-06-25T20:12:45.408619Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "memo": "YWEyNDVlMjgtZGIyYS00YmRjLThkODgtYzExYmJhM2Y=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          }
        ]
      """

private const val SEP_6_WITHDRAW_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal-exchange",
              "status": "pending_user_transfer_start",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:14:07.562913Z",
              "updated_at": "2024-06-25T20:14:08.587470Z",
              "message": "test message 1",
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "MjMwYzFlNjgtZTc3MC00ZTI5LTlhNDktNWM3OGJmZGY=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal-exchange",
              "status": "pending_anchor",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:14:07.562913Z",
              "updated_at": "2024-06-25T20:14:09.630266Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "MjMwYzFlNjgtZTc3MC00ZTI5LTlhNDktNWM3OGJmZGY=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal-exchange",
              "status": "completed",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:14:07.562913Z",
              "updated_at": "2024-06-25T20:14:10.644753Z",
              "completed_at": "2024-06-25T20:14:10.644752Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "memo": "MjMwYzFlNjgtZTc3MC00ZTI5LTlhNDktNWM3OGJmZGY=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          }
        ]
      """

private const val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_onchain_funds",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_out": {
                "amount": "95",
                "asset": "iso4217:USD"
              },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_expected": {
                "amount": "100"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_onchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          },
          {
            "id": "3",
            "method": "notify_offchain_funds_pending",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "external_transaction_id": "ext-123456"
            }
          },
          {
            "id": "4",
            "method": "notify_offchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 4",
              "external_transaction_id": "ext-123456"
            }
          }
        ]
      """

private const val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "started_at": "2024-06-25T20:15:25.028960Z",
              "updated_at": "2024-06-25T20:15:26.050724Z",
              "message": "test message 1",
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "MTIxYmNmNjctN2IxYy00N2IwLTg1NDktZWU0ZGY4ODg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_anchor",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "started_at": "2024-06-25T20:15:25.028960Z",
              "updated_at": "2024-06-25T20:15:27.109470Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "MTIxYmNmNjctN2IxYy00N2IwLTg1NDktZWU0ZGY4ODg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_external",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "started_at": "2024-06-25T20:15:25.028960Z",
              "updated_at": "2024-06-25T20:15:28.131905Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "memo": "MTIxYmNmNjctN2IxYy00N2IwLTg1NDktZWU0ZGY4ODg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "completed",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "started_at": "2024-06-25T20:15:25.028960Z",
              "updated_at": "2024-06-25T20:15:29.175950Z",
              "completed_at": "2024-06-25T20:15:29.175948Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 4",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "memo": "MTIxYmNmNjctN2IxYy00N2IwLTg1NDktZWU0ZGY4ODg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "4"
          }
        ]
      """

private const val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_onchain_funds",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": {
                "amount": "95",
                "asset": "iso4217:USD"
              },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_expected": {
                "amount": "100"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_onchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",
              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          },
          {
            "id": "3",
            "method": "notify_offchain_funds_available",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "external_transaction_id": "ext-123456"
            }
          },
          {
            "id": "4",
            "method": "notify_offchain_funds_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 4",
              "external_transaction_id": "ext-123456"
            }
          }
        ]
      """

private const val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T19:55:51.246352Z",
              "updated_at": "2024-06-25T19:55:52.305301Z",
              "message": "test message 1",
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "MjJkMmM1MjEtMmQ4MS00ZmIxLWE0ZGItZjhjMDdiZjg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_anchor",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T19:55:51.246352Z",
              "updated_at": "2024-06-25T19:55:53.485764Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "MjJkMmM1MjEtMmQ4MS00ZmIxLWE0ZGItZjhjMDdiZjg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_user_transfer_complete",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T19:55:51.246352Z",
              "updated_at": "2024-06-25T19:55:54.603835Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "memo": "MjJkMmM1MjEtMmQ4MS00ZmIxLWE0ZGItZjhjMDdiZjg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "completed",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T19:55:51.246352Z",
              "updated_at": "2024-06-25T19:55:55.646802Z",
              "completed_at": "2024-06-25T19:55:55.646799Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 4",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "memo": "MjJkMmM1MjEtMmQ4MS00ZmIxLWE0ZGItZjhjMDdiZjg=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "4"
          }
        ]
      """

private const val SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS =
  """
        [
          {
            "id": "1",
            "method": "request_onchain_funds",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 1",
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": {
                "amount": "95",
                "asset": "iso4217:USD"
              },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_expected": {
                "amount": "100"
              }
            }
          },
          {
            "id": "2",
            "method": "notify_onchain_funds_received",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 2",

              "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
            }
          },
          {
            "id": "3",
            "method": "notify_refund_sent",
            "jsonrpc": "2.0",
            "params": {
              "transaction_id": "%TX_ID%",
              "message": "test message 3",
              "refund": {
                "id": "123456",
                "amount": {
                  "amount": 95,
                  "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                },
                "amount_fee": {
                  "amount": 5,
                  "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                }
              }
            }
          }
        ]
      """

private const val SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:17:11.692327Z",
              "updated_at": "2024-06-25T20:17:12.718879Z",
              "message": "test message 1",
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "ZGU5YmVmZGMtOGFlNy00ZWJkLWFkYWYtNGE5YjcxOWI=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "1"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "pending_anchor",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:17:11.692327Z",
              "updated_at": "2024-06-25T20:17:13.780781Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "ZGU5YmVmZGMtOGFlNy00ZWJkLWFkYWYtNGE5YjcxOWI=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "2"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "6",
              "kind": "withdrawal",
              "status": "refunded",
              "type": "bank_account",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": {
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_out": { "amount": "95", "asset": "iso4217:USD" },
              "fee_details": {
                "total": "5",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "started_at": "2024-06-25T20:17:11.692327Z",
              "updated_at": "2024-06-25T20:17:14.793085Z",
              "completed_at": "2024-06-25T20:17:14.793084Z",
              "transfer_received_at": "2024-06-13T20:02:49Z",
              "message": "test message 3",
              "refunds": {
                "amount_refunded": {
                  "amount": "100",
                  "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                },
                "amount_fee": {
                  "amount": "5",
                  "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "stellar",
                    "amount": {
                      "amount": "95",
                      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                    },
                    "fee": {
                      "amount": "5",
                      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
                    }
                  }
                ]
              },
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
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
              "source_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "ZGU5YmVmZGMtOGFlNy00ZWJkLWFkYWYtNGE5YjcxOWI=",
              "memo_type": "id",
              "client_name": "referenceCustodial",
              "customers": {
                "sender": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                },
                "receiver": {
                  "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
                }
              }
            },
            "id": "3"
          }
        ]
      """

private const val SEP_6_WITHDRAW_FLOW_REQUEST =
  """
        {
          "asset_code": "USDC",
          "type": "bank_account",
          "amount": "1"
        }
      """

private const val SEP_6_WITHDRAW_EXCHANGE_FLOW_REQUEST =
  """
        {
          "destination_asset": "iso4217:USD",
          "source_asset": "USDC",
          "amount": "1",
          "type": "bank_account"
        }
      """

private const val SEP_6_DEPOSIT_FLOW_REQUEST =
  """
        {
          "asset_code": "USDC",
          "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
          "amount": "1",
          "type": "SWIFT"
        }
      """

private const val SEP_6_DEPOSIT_EXCHANGE_FLOW_REQUEST =
  """
        {
          "destination_asset": "USDC",
          "source_asset": "iso4217:USD",
          "amount": "1",
          "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
          "type": "SWIFT"
        }
      """
