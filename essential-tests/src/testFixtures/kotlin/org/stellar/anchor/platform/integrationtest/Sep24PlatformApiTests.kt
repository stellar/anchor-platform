package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Sep24PlatformApiTests : PlatformApiTests() {

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_onchain_funds_sent
   * 4. completed
   */
  @Test
  @Order(10)
  fun `SEP-24 deposit complete short flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> notify_interactive_flow_completed
   * 2. pending_anchor -> request_offchain_funds
   * 3. pending_user_transfer_start -> notify_offchain_funds_received
   * 4. pending_anchor -> request_trust
   * 5. pending_trust -> notify_trust_set
   * 6. pending_anchor -> notify_onchain_funds_sent
   * 7. completed
   */
  @Test
  @Order(20)
  fun `SEP-24 deposit complete full with trust flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES,
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
  @Order(30)
  fun `SEP-24 deposit complete full with recovery flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES,
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
  fun `SEP-24 deposit complete short partial refund flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_sent
   * 4. completed
   */
  @Test
  @Order(50)
  fun `SEP-24 withdraw complete short flow`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES,
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
  @Order(60)
  fun `SEP-24 withdraw complete full via pending external`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES,
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
  @Order(70)
  fun `SEP-24 withdraw complete full via pending user`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES,
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_refund_sent
   * 4. refunded
   */
  @Test
  @Order(80)
  fun `SEP-24 withdraw full refund`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES,
    )
  }

  @Test
  @Order(90)
  fun `SEP-24 test validations and errors`() {
    `test sep24 deposit flow`(VALIDATIONS_AND_ERRORS_REQUESTS, VALIDATIONS_AND_ERRORS_RESPONSES)
  }

  private fun `test sep24 withdraw flow`(actionRequests: String, actionResponse: String) {
    val withdrawRequest = gson.fromJson(SEP_24_WITHDRAW_FLOW_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val withdrawResponse = sep24Client.withdraw(withdrawRequest as HashMap<String, String>)
    `test flow`(withdrawResponse.id, actionRequests, actionResponse)
  }

  private fun `test sep24 deposit flow`(actionRequests: String, actionResponse: String) {
    val depositRequest = gson.fromJson(SEP_24_DEPOSIT_FLOW_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    `test flow`(depositResponse.id, actionRequests, actionResponse)
  }
}

private const val SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
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

private const val SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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
                "amount": "100",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:18:34.205694Z",
              "updated_at": "2024-06-25T20:18:35.274007Z",
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
              "status": "pending_anchor",
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
              "started_at": "2024-06-25T20:18:34.205694Z",
              "updated_at": "2024-06-25T20:18:36.290857Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
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
              "status": "completed",
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
              "started_at": "2024-06-25T20:18:34.205694Z",
              "updated_at": "2024-06-25T20:18:37.353640Z",
              "completed_at": "2024-06-25T20:18:37.353643Z",
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
              "client_name": "referenceCustodial"
            },
            "id": "3"
          }
        ]
      """

private const val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS =
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

private const val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES =
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
              "amount_in": { "amount": "100", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "95",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "5", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:19:48.818752Z",
              "updated_at": "2024-06-25T20:19:49.849169Z",
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
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:19:48.818752Z",
              "updated_at": "2024-06-25T20:19:50.877987Z",
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
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:19:48.818752Z",
              "updated_at": "2024-06-25T20:19:51.894806Z",
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
              "status": "pending_trust",
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
              "started_at": "2024-06-25T20:19:48.818752Z",
              "updated_at": "2024-06-25T20:19:52.914135Z",
              "message": "test message 4",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial"
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "pending_anchor",
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
              "started_at": "2024-06-25T20:19:48.818752Z",
              "updated_at": "2024-06-25T20:19:53.940893Z",
              "message": "test message 5",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial"
            },
            "id": "5"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "completed",
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
              "started_at": "2024-06-25T20:19:48.818752Z",
              "updated_at": "2024-06-25T20:19:55.012577Z",
              "completed_at": "2024-06-25T20:19:55.012579Z",
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
              "client_name": "referenceCustodial"
            },
            "id": "6"
          }
        ]
      """

private const val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS =
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
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
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

private const val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES =
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
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:21:24.266127Z",
              "updated_at": "2024-06-25T20:21:25.299818Z",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:21:24.266127Z",
              "updated_at": "2024-06-25T20:21:26.312882Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
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
              "status": "error",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:21:24.266127Z",
              "updated_at": "2024-06-25T20:21:27.330339Z",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:21:24.266127Z",
              "updated_at": "2024-06-25T20:21:28.350485Z",
              "message": "test message 4",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial"
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "completed",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:21:24.266127Z",
              "updated_at": "2024-06-25T20:21:29.399867Z",
              "completed_at": "2024-06-25T20:21:29.399869Z",
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
              "client_name": "referenceCustodial"
            },
            "id": "5"
          }
        ]
      """

private const val SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS =
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
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
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

private const val SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES =
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
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "10.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:24:52.680611Z",
              "updated_at": "2024-06-25T20:24:53.700986Z",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:24:52.680611Z",
              "updated_at": "2024-06-25T20:24:54.714524Z",
              "message": "test message 2",
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
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
              "status": "pending_external",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:24:52.680611Z",
              "updated_at": "2024-06-25T20:24:55.734052Z",
              "message": "test message 3",
              "refunds": {
                "amount_refunded": { "amount": "990.11", "asset": "iso4217:USD" },
                "amount_fee": { "amount": "1", "asset": "iso4217:USD" },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "stellar",
                    "amount": { "amount": "989.11", "asset": "iso4217:USD" },
                    "fee": { "amount": "1", "asset": "iso4217:USD" }
                  }
                ]
              },
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:24:52.680611Z",
              "updated_at": "2024-06-25T20:24:56.756149Z",
              "message": "test message 4",
              "refunds": {
                "amount_refunded": { "amount": "990.11", "asset": "iso4217:USD" },
                "amount_fee": { "amount": "1", "asset": "iso4217:USD" },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "stellar",
                    "amount": { "amount": "989.11", "asset": "iso4217:USD" },
                    "fee": { "amount": "1", "asset": "iso4217:USD" }
                  }
                ]
              },
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "external_transaction_id": "ext-123456",
              "client_name": "referenceCustodial"
            },
            "id": "4"
          },
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "deposit",
              "status": "completed",
              "amount_expected": {
                "amount": "10.11",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "amount_in": { "amount": "1000.11", "asset": "iso4217:USD" },
              "amount_out": {
                "amount": "9",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee_details": { "total": "1.11", "asset": "iso4217:USD" },
              "started_at": "2024-06-25T20:24:52.680611Z",
              "updated_at": "2024-06-25T20:24:57.925920Z",
              "completed_at": "2024-06-25T20:24:57.925922Z",
              "message": "test message 5",
              "refunds": {
                "amount_refunded": { "amount": "990.11", "asset": "iso4217:USD" },
                "amount_fee": { "amount": "1", "asset": "iso4217:USD" },
                "payments": [
                  {
                    "id": "123456",
                    "id_type": "stellar",
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
              "client_name": "referenceCustodial"
            },
            "id": "5"
          }
        ]
      """

private const val SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
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

private const val SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:26:19.128384Z",
              "updated_at": "2024-06-25T20:26:20.153836Z",
              "message": "test message 1",
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "ZjdiMzQ0YmUtZjNlZC00NWYwLThlNWItYWQ0NjAzMzY=",
              "memo_type": "hash",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:26:19.128384Z",
              "updated_at": "2024-06-25T20:26:21.203470Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZjdiMzQ0YmUtZjNlZC00NWYwLThlNWItYWQ0NjAzMzY=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "ZjdiMzQ0YmUtZjNlZC00NWYwLThlNWItYWQ0NjAzMzY=",
              "memo_type": "hash",
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
              "status": "completed",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:26:19.128384Z",
              "updated_at": "2024-06-25T20:26:22.218796Z",
              "completed_at": "2024-06-25T20:26:22.218797Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZjdiMzQ0YmUtZjNlZC00NWYwLThlNWItYWQ0NjAzMzY=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "external_transaction_id": "ext-123456",
              "memo": "ZjdiMzQ0YmUtZjNlZC00NWYwLThlNWItYWQ0NjAzMzY=",
              "memo_type": "hash",
              "client_name": "referenceCustodial"
            },
            "id": "3"
          }
        ]
      """

private const val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS =
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

private const val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:27:29.594082Z",
              "updated_at": "2024-06-25T20:27:30.616647Z",
              "message": "test message 1",
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
              "memo_type": "hash",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:27:29.594082Z",
              "updated_at": "2024-06-25T20:27:31.668161Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
              "memo_type": "hash",
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
              "status": "pending_external",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:27:29.594082Z",
              "updated_at": "2024-06-25T20:27:32.684285Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "external_transaction_id": "ext-123456",
              "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
              "memo_type": "hash",
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
              "status": "completed",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:27:29.594082Z",
              "updated_at": "2024-06-25T20:27:33.698792Z",
              "completed_at": "2024-06-25T20:27:33.698794Z",
              "message": "test message 4",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "external_transaction_id": "ext-123456",
              "memo": "NGQwMDk3NTgtODg3My00OGE1LWE4M2UtYTllOGU0OGM=",
              "memo_type": "hash",
              "client_name": "referenceCustodial"
            },
            "id": "4"
          }
        ]
      """

private const val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES =
  """
        [
          {
            "jsonrpc": "2.0",
            "result": {
              "id": "%TX_ID%",
              "sep": "24",
              "kind": "withdrawal",
              "status": "pending_user_transfer_start",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:28:41.409930Z",
              "updated_at": "2024-06-25T20:28:42.430584Z",
              "message": "test message 1",
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
              "memo_type": "hash",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:28:41.409930Z",
              "updated_at": "2024-06-25T20:28:43.477431Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
              "memo_type": "hash",
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
              "status": "pending_user_transfer_complete",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:28:41.409930Z",
              "updated_at": "2024-06-25T20:28:44.490809Z",
              "message": "test message 3",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "external_transaction_id": "ext-123456",
              "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
              "memo_type": "hash",
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
              "status": "completed",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:28:41.409930Z",
              "updated_at": "2024-06-25T20:28:45.505773Z",
              "completed_at": "2024-06-25T20:28:45.505775Z",
              "message": "test message 4",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "external_transaction_id": "ext-123456",
              "memo": "ZWZhNWI5YWUtNWJiNS00ZmQyLThiZjQtOWY5M2NmNmY=",
              "memo_type": "hash",
              "client_name": "referenceCustodial"
            },
            "id": "4"
          }
        ]
      """

private const val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS =
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

private const val SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS =
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
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 3",
      "refund": {
        "id": "123456",
        "amount": {
          "amount": 95,
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": 5,
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
              "status": "pending_user_transfer_start",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:29:52.637982Z",
              "updated_at": "2024-06-25T20:29:53.671452Z",
              "message": "test message 1",
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "NmUyZTcyYjktNzIyMC00OGRiLTkwZDItNDkyOWU1OWU=",
              "memo_type": "hash",
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
              "status": "pending_anchor",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:29:52.637982Z",
              "updated_at": "2024-06-25T20:29:54.713859Z",
              "message": "test message 2",
              "stellar_transactions": [
                {
                  "id": "%TESTPAYMENT_TXN_HASH%",
                  "memo": "NmUyZTcyYjktNzIyMC00OGRiLTkwZDItNDkyOWU1OWU=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "NmUyZTcyYjktNzIyMC00OGRiLTkwZDItNDkyOWU1OWU=",
              "memo_type": "hash",
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
              "status": "refunded",
              "amount_expected": {
                "amount": "100",
                "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
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
              "started_at": "2024-06-25T20:29:52.637982Z",
              "updated_at": "2024-06-25T20:29:55.727918Z",
              "completed_at": "2024-06-25T20:29:55.727920Z",
              "message": "test message 3",
              "refunds": {
                "amount_refunded": {
                  "amount": "100",
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
                      "amount": "95",
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
                  "memo": "NmUyZTcyYjktNzIyMC00OGRiLTkwZDItNDkyOWU1OWU=",
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
              "source_account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
              "destination_account": "GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF",
              "memo": "NmUyZTcyYjktNzIyMC00OGRiLTkwZDItNDkyOWU1OWU=",
              "memo_type": "hash",
              "client_name": "referenceCustodial"
            },
            "id": "3"
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

private const val VALIDATIONS_AND_ERRORS_REQUESTS =
  """
[
  {
    "id": "1",
    "method": "notify_interactive_flow_completed",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "TX_1",
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
    "method": "notify_interactive_flow_completed",
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
        "amount": "3"
      }
    }
  },
  {
    "id": "3",
    "method": "notify_interactive_flow_completed",
    "jsonrpc": "3.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 3",
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
    "id": "4",
    "method": "unsupported method",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 4",
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
    "id": "5",
    "method": "request_onchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 5",
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
    "id": "6",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 6",
      "amount_in": {
        "amount": "10.11",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_out": {
        "amount": "9",
        "asset": "iso4217:USD"
      },
      "fee_details": {
        "total": "1.11",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "amount_expected": {
        "amount": "10.11"
      }
    }
  },
  {
    "id": "7",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 7",
      "amount_in": {
        "amount": "0",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "9",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5iso4217:USD"
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
    "id": "8",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 8",
      "amount_in": {
        "amount": "10.11",
        "asset": "iso4217:USD"
      },
      "amount_out": {
        "amount": "0",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5iso4217:USD"
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
    "id": "9",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 9",
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
        "asset": "iso111:III"
      },
      "amount_expected": {
        "amount": "10.11"
      }
    }
  },
  {
    "id": "10",
    "method": "notify_interactive_flow_completed",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 10",
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
    "id": "11",
    "method": "notify_interactive_flow_completed",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 11",
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
    "id": "12",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 12",
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
    "id": "13",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 13",
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
    "id": "14",
    "method": "notify_onchain_funds_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 14",
      "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
    }
  },
  {
    "id": "15",
    "method": "notify_offchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 15",
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
    "id": "16",
    "method": "notify_offchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 16",
      "funds_received_at": "2023-07-04T12:34:56Z",
      "external_transaction_id": "ext-123456",
      "amount_in": {
        "amount": "10.11"
      },
      "amount_out": {
        "amount": "9"
      },
      "fee_details": {
        "total": "1.11"
      },
      "amount_expected": {
        "amount": "10.11"
      }
    }
  },
  {
    "id": "17",
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 17",
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
    "id": "18",
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 18",
      "refund": {
        "id": "123456",
        "amount": {
          "amount": "989.11",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
          "amount": "1",
          "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        }
      }
    }
  },
  {
    "id": "19",
    "method": "notify_onchain_funds_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 19",
      "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
    }
  },
  {
    "id": "20",
    "method": "notify_onchain_funds_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 20",
      "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
    }
  }
]
  """

private const val VALIDATIONS_AND_ERRORS_RESPONSES =
  """
[
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "TX_1",
      "code": -32600,
      "message": "Transaction with id[TX_1] is not found"
    },
    "id": "1"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "Id can't be NULL"
    }
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "Unsupported JSON-RPC protocol version[3.0]"
    },
    "id": "3"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32601,
      "message": "No matching RPC method[unsupported method]"
    },
    "id": "4"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "RPC method[request_onchain_funds] is not supported. Status[incomplete], kind[deposit], protocol[24], funds received[false]"
    },
    "id": "5"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32602,
      "message": "amount_in.asset should be non-stellar asset"
    },
    "id": "6"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32602,
      "message": "'stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5iso4217:USD' is not a supported asset."
    },
    "id": "7"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32602,
      "message": "'stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5iso4217:USD' is not a supported asset."
    },
    "id": "8"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32602,
      "message": "'iso111:III' is not a supported asset."
    },
    "id": "9"
  },
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
      "message": "test message 10",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "client_name": "referenceCustodial"
    },
    "id": "10"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "RPC method[notify_interactive_flow_completed] is not supported. Status[pending_anchor], kind[deposit], protocol[24], funds received[false]"
    },
    "id": "11"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "pending_user_transfer_start",
      "amount_expected": {
        "amount": "10.11",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
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
      "message": "test message 12",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
    },
    "id": "12"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "RPC method[request_offchain_funds] is not supported. Status[pending_user_transfer_start], kind[deposit], protocol[24], funds received[false]"
    },
    "id": "13"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_user_transfer_start], kind[deposit], protocol[24], funds received[false]"
    },
    "id": "14"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "pending_anchor",
      "amount_expected": {
        "amount": "10.11",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
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
      "message": "test message 15",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "external_transaction_id": "ext-123456"
    },
    "id": "15"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "RPC method[notify_offchain_funds_received] is not supported. Status[pending_anchor], kind[deposit], protocol[24], funds received[true]"
    },
    "id": "16"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32602,
      "message": "Refund amount exceeds amount_in"
    },
    "id": "17"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32602,
      "message": "refund.amount.asset does not match transaction amount_in_asset"
    },
    "id": "18"
  },
  {
    "jsonrpc": "2.0",
    "result": {
      "id": "%TX_ID%",
      "sep": "24",
      "kind": "deposit",
      "status": "completed",
      "amount_expected": {
        "amount": "10.11",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
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
      "message": "test message 19",
      "stellar_transactions": [
        {
          "payments": [
            {
              "amount": {
                "amount": "0.0002000",
                "asset": "%TESTPAYMENT_ASSET_CIRCLE_USDC%"
              },
              "payment_type": "payment",
              "source_account": "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
              "destination_account": "GBDYDBJKQBJK4GY4V7FAONSFF2IBJSKNTBYJ65F5KCGBY2BIGPGGLJOH"
            }
          ]
        }
      ],
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "external_transaction_id": "ext-123456"
    },
    "id": "19"
  },
  {
    "jsonrpc": "2.0",
    "error": {
      "id": "%TX_ID%",
      "code": -32600,
      "message": "RPC method[notify_onchain_funds_sent] is not supported. Status[completed], kind[deposit], protocol[24], funds received[true]"
    },
    "id": "20"
  }
]
  """
