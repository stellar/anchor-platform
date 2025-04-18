package org.stellar.anchor.platform.extendedtest.custody

import java.util.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.skyscreamer.jsonassert.Customization
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.comparator.CustomComparator
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.rpc.method.*
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.client.CustodyApiClient
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson
import org.stellar.anchor.util.RSAUtil

class CustodyApiTests : AbstractIntegrationTests(TestConfig("custody")) {
  companion object {
    const val CUSTODY_TX_ID_KEY = "%CUSTODY_TX_ID%"
    private val custodyTxnId = UUID.randomUUID().toString()
    private val refundCustodyTxnId = UUID.randomUUID().toString()
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
                .setBody(
                  CUSTODY_TRANSACTION_PAYMENT_RESPONSE.replace(CUSTODY_TX_ID_KEY, custodyTxnId)
                )
            }
            return MockResponse().setResponseCode(404)
          }
        }
      custodyMockServer.start(58086)
    }

    @AfterAll
    @JvmStatic
    fun destroy() {
      custodyMockServer.shutdown()
    }
  }

  private val custodyApiClient =
    CustodyApiClient(
      config.env["custody.server.url"]!!,
      token.token,
      RSAUtil.generatePrivateKey(config.env["secret.custody.fireblocks.secret_key"]),
    )
  private val sep24Client = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), token.token)
  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)

  @Test
  fun `test generate deposit address`() {
    val ex: SepException = assertThrows { custodyApiClient.generateDepositAddress("invalidAsset") }
    Assertions.assertEquals(
      "{\"error\":\"Unable to find Fireblocks asset code by Stellar asset code [invalidAsset]\"}",
      ex.message,
    )

    val depositAddressResponse =
      custodyApiClient.generateDepositAddress(
        "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      )
    JSONAssert.assertEquals(
      EXPECTED_DEPOSIT_ADDRESS,
      gson.toJson(depositAddressResponse),
      JSONCompareMode.STRICT,
    )

    for (count in 1..custodyMockServer.requestCount) {
      val recordedRequest = custodyMockServer.takeRequest()
      if (
        recordedRequest.method.equals("POST") &&
          recordedRequest.path.toString() == "//v1/vault/accounts/1/XLM_USDC_T_CEKS/addresses"
      ) {
        Assertions.assertEquals(
          "//v1/vault/accounts/1/XLM_USDC_T_CEKS/addresses",
          recordedRequest.path.toString(),
        )
        JSONAssert.assertEquals(
          CUSTODY_DEPOSIT_ADDRESS_REQUEST,
          recordedRequest.body.readUtf8(),
          JSONCompareMode.STRICT,
        )
      }
    }
  }

  @Test
  fun `test custody transaction payment`() {
    val depositRequest = gson.fromJson(DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId = depositResponse.id

    custodyApiClient.createTransaction(
      gson.fromJson(
        inject(CUSTODY_TRANSACTION_REQUEST, TX_ID_KEY to txId),
        CreateCustodyTransactionRequest::class.java,
      )
    )

    val requestOffchainFundsParams =
      gson.fromJson(REQUEST_OFFCHAIN_FUNDS_REQUEST, RequestOffchainFundsRequest::class.java)
    requestOffchainFundsParams.transactionId = txId
    platformApiClient.sendRpcNotification(
      RpcMethod.REQUEST_OFFCHAIN_FUNDS,
      requestOffchainFundsParams,
    )

    var txResponse = platformApiClient.getTransaction(txId)
    Assertions.assertEquals(SepTransactionStatus.PENDING_USR_TRANSFER_START, txResponse.status)

    val notifyOffchainFundsReceivedParams =
      gson.fromJson(
        NOTIFY_OFFCHAIN_FUNDS_RECEIVED_REQUEST,
        NotifyOffchainFundsReceivedRequest::class.java,
      )
    notifyOffchainFundsReceivedParams.transactionId = txId
    platformApiClient.sendRpcNotification(
      RpcMethod.NOTIFY_OFFCHAIN_FUNDS_RECEIVED,
      notifyOffchainFundsReceivedParams,
    )

    txResponse = platformApiClient.getTransaction(txId)
    Assertions.assertEquals(SepTransactionStatus.PENDING_ANCHOR, txResponse.status)

    val ex: SepException = assertThrows { custodyApiClient.createTransactionPayment("invalidId") }
    Assertions.assertInstanceOf(SepNotFoundException::class.java, ex)

    custodyApiClient.createTransactionPayment(txId)

    var found = false
    for (count in 1..custodyMockServer.requestCount) {
      val recordedRequest = custodyMockServer.takeRequest()
      if (
        recordedRequest.method.equals("POST") &&
          recordedRequest.path.toString() == "//v1/transactions"
      ) {
        JSONAssert.assertEquals(
          CUSTODY_TRANSACTION_PAYMENT_REQUEST,
          recordedRequest.body.readUtf8(),
          JSONCompareMode.STRICT,
        )

        val webhookRequest = inject(WEBHOOK_REQUEST, CUSTODY_TX_ID_KEY to custodyTxnId)
        custodyApiClient.sendWebhook(webhookRequest)

        txResponse = platformApiClient.getTransaction(txId)
        txResponse.startedAt = null
        txResponse.updatedAt = null

        JSONAssert.assertEquals(
          inject(EXPECTED_TRANSACTION_RESPONSE, TX_ID_KEY to txId),
          gson.toJson(txResponse),
          CustomComparator(
            JSONCompareMode.LENIENT,
            Customization("completed_at") { _, _ -> true },
            Customization("stellar_transactions[0].created_at") { _, _ -> true },
          ),
        )
        found = true
      }
    }
    assertTrue(found)
  }

  @Test
  fun `test custody transaction refund`() {
    val withdrawRequest = gson.fromJson(SEP_24_WITHDRAW_FLOW_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val withdrawResponse = sep24Client.withdraw(withdrawRequest as HashMap<String, String>)
    val txId = withdrawResponse.id

    val mockServerDispatcher: Dispatcher =
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
              .setBody(
                inject(CUSTODY_TRANSACTION_REFUND_RESPONSE, CUSTODY_TX_ID_KEY to refundCustodyTxnId)
              )
          }
          return MockResponse().setResponseCode(404)
        }
      }

    custodyMockServer.dispatcher = mockServerDispatcher

    val requestOnchainFundsParams =
      gson.fromJson(
        inject(REQUEST_ONCHAIN_FUNDS_REQUEST, TX_ID_KEY to txId),
        RequestOnchainFundsRequest::class.java,
      )
    platformApiClient.sendRpcNotification(
      RpcMethod.REQUEST_ONCHAIN_FUNDS,
      requestOnchainFundsParams,
    )

    var txResponse = platformApiClient.getTransaction(txId)
    Assertions.assertEquals(SepTransactionStatus.PENDING_USR_TRANSFER_START, txResponse.status)

    val notifyOnchainFundsReceivedRequest =
      gson.fromJson(
        inject(NOTIFY_ONCHAIN_FUNDS_RECEIVED_REQUEST, TX_ID_KEY to txId),
        NotifyOnchainFundsReceivedRequest::class.java,
      )
    platformApiClient.sendRpcNotification(
      RpcMethod.NOTIFY_ONCHAIN_FUNDS_RECEIVED,
      notifyOnchainFundsReceivedRequest,
    )

    txResponse = platformApiClient.getTransaction(txId)
    Assertions.assertEquals(SepTransactionStatus.PENDING_ANCHOR, txResponse.status)

    val doStellarRefundParams =
      gson.fromJson(
        inject(DO_STELLAR_REFUND_REQUEST, TX_ID_KEY to txId),
        DoStellarRefundRequest::class.java,
      )
    platformApiClient.sendRpcNotification(RpcMethod.DO_STELLAR_REFUND, doStellarRefundParams)
    txResponse = platformApiClient.getTransaction(txId)
    Assertions.assertEquals(SepTransactionStatus.PENDING_STELLAR, txResponse.status)

    custodyApiClient.sendWebhook(
      inject(REFUND_WEBHOOK_REQUEST, CUSTODY_TX_ID_KEY to refundCustodyTxnId)
    )

    txResponse = platformApiClient.getTransaction(txId)
    txResponse.startedAt = null
    txResponse.updatedAt = null

    JSONAssert.assertEquals(
      inject(EXPECTED_TXN_REFUND_RESPONSE, TX_ID_KEY to txId),
      gson.toJson(txResponse),
      CustomComparator(
        JSONCompareMode.LENIENT,
        Customization("completed_at") { _, _ -> true },
        Customization("stellar_transactions[0].created_at") { _, _ -> true },
      ),
    )
  }
}

private const val DEPOSIT_REQUEST =
  """{
    "asset_code": "USDC",
    "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
    "lang": "en"
}"""

private const val SEP_24_WITHDRAW_FLOW_REQUEST =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
    "lang": "en",
    "refund_memo":  "12345",
    "refund_memo_type": "id"
}"""

private const val EXPECTED_DEPOSIT_ADDRESS =
  """
  {
    "memoType":"id",
    "memo": "testTag",
    "address": "testAddress"
  }
"""

private const val CUSTODY_DEPOSIT_ADDRESS_REQUEST = """
  {
  }
"""

private const val CUSTODY_DEPOSIT_ADDRESS_RESPONSE =
  """
  {
    "address":"testAddress",
    "legacyAddress": "testLegacyAddress",
    "enterpriseAddress": "testEnterpriseAddress",
    "tag":"testTag",
    "bip44AddressIndex":12345
  }
"""

private const val CUSTODY_TRANSACTION_REQUEST =
  """
  {
    "id" : "%TX_ID%",
    "memo":  "testMemo",
    "memoType": "testMemoType",
    "protocol": "24",
    "fromAccount": "testFromAccount",
    "toAccount": "testToAccount",
    "amount": "0.45",
    "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
    "kind": "deposit",
    "requestAssetCode": "testRequestAssetCode",
    "requestAssetIssuer": "testRequestAssetIssuer"
  }
"""

private const val CUSTODY_TRANSACTION_PAYMENT_REQUEST =
  """
    {
      "assetId": "XLM_USDC_T_CEKS",
      "source": {
        "type": "VAULT_ACCOUNT",
        "id": "1"
      },
      "destination": {
        "type": "ONE_TIME_ADDRESS",
        "oneTimeAddress": {
          "address": "testToAccount",
          "tag": "testMemo"
        }
      },
      "amount": "0.45"
    }
"""

private const val CUSTODY_TRANSACTION_PAYMENT_RESPONSE =
  """
    {
      "id":"%CUSTODY_TX_ID%",
      "status": "SUBMITTED"
    }
"""

private const val CUSTODY_TRANSACTION_REFUND_RESPONSE =
  """
    {
      "id":"%CUSTODY_TX_ID%",
      "status": "SUBMITTED"
    }
"""

private const val WEBHOOK_REQUEST =
  """
  {
  "type": "TRANSACTION_STATUS_UPDATED",
  "tenantId": "6ae8e895-7bdb-5021-b865-c65885c61068",
  "timestamp": 1687423621679,
  "data": {
    "id": "%CUSTODY_TX_ID%",
    "createdAt": 1687423599336,
    "lastUpdated": 1687423620808,
    "assetId": "XLM_USDC_T_CEKS",
    "source": {
      "id": "4",
      "type": "VAULT_ACCOUNT",
      "name": "NewVault",
      "subType": ""
    },
    "destination": {
      "id": null,
      "type": "ONE_TIME_ADDRESS",
      "name": "N/A",
      "subType": ""
    },
    "amount": 1,
    "networkFee": 0.00001,
    "netAmount": 1,
    "sourceAddress": "%TESTPAYMENT_SRC_ACCOUNT%",
    "destinationAddress": "%TESTPAYMENT_DEST_ACCOUNT%",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "CONFIRMING",
    "txHash": "%TESTPAYMENT_TXN_HASH%",
    "subStatus": "CONFIRMED",
    "signedBy": [],
    "createdBy": "1444ed36-5bc0-4e3b-9b17-5df29fc0590f",
    "rejectedBy": "",
    "amountUSD": 1,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 1,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "customerRefId": null,
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "1",
      "requestedAmount": "1",
      "netAmount": "1",
      "amountUSD": "1"
    },
    "feeInfo": {
      "networkFee": "0.00001"
    },
    "destinations": [],
    "externalTxId": null,
    "blockInfo": {
      "blockHeight": "131155",
      "blockHash": "46185b809c09e29da09bdc667c947b23fb2716ed8735227c0e77c19fdb620fd4"
    },
    "signedMessages": [],
    "assetType": "XLM_ASSET"
  }
}
"""

private const val REFUND_WEBHOOK_REQUEST =
  """
  {
  "type": "TRANSACTION_STATUS_UPDATED",
  "tenantId": "6ae8e895-7bdb-5021-b865-c65885c61068",
  "timestamp": 1687423621679,
  "data": {
    "id": "%CUSTODY_TX_ID%",
    "createdAt": 1687423599336,
    "lastUpdated": 1687423620808,
    "assetId": "XLM_USDC_T_CEKS",
    "source": {
      "id": "4",
      "type": "VAULT_ACCOUNT",
      "name": "NewVault",
      "subType": ""
    },
    "destination": {
      "id": null,
      "type": "ONE_TIME_ADDRESS",
      "name": "N/A",
      "subType": ""
    },
    "amount": 1,
    "networkFee": 0.00001,
    "netAmount": 1,
    "sourceAddress": "%TESTPAYMENT_DEST_ACCOUNT%",
    "destinationAddress": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
    "destinationAddressDescription": "",
    "destinationTag": "12345",
    "status": "CONFIRMING",
    "txHash": "%TESTPAYMENT_TXN_HASH%",
    "subStatus": "CONFIRMED",
    "signedBy": [],
    "createdBy": "1444ed36-5bc0-4e3b-9b17-5df29fc0590f",
    "rejectedBy": "",
    "amountUSD": 1,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 1,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "customerRefId": null,
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "1",
      "requestedAmount": "1",
      "netAmount": "1",
      "amountUSD": "1"
    },
    "feeInfo": {
      "networkFee": "0.00001"
    },
    "destinations": [],
    "externalTxId": null,
    "blockInfo": {
      "blockHeight": "131155",
      "blockHash": "46185b809c09e29da09bdc667c947b23fb2716ed8735227c0e77c19fdb620fd4"
    },
    "signedMessages": [],
    "assetType": "XLM_ASSET"
  }
}
"""

private const val EXPECTED_TRANSACTION_RESPONSE =
  """
  {
  "id": "%TX_ID%",
  "sep": "24",
  "kind": "deposit",
  "status": "completed",
  "amount_in": {
    "amount": "1",
    "asset": "iso4217:USD"
  },
  "message": "Outgoing payment sent",
  "amount_out": {
    "amount": "0.9",
    "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
  },
  "fee_details": {
    "total": "0.1",
    "asset": "iso4217:USD"
  },
  "amount_expected": {
    "amount": "1",
    "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
  },
  "completed_at": "2023-06-22T08:46:39.336Z",
  "external_transaction_id": "1",
  "stellar_transactions": [
    {
      "id": "%TESTPAYMENT_TXN_HASH%",
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
  "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
  "client_name": "referenceCustodial"
}
"""

private const val EXPECTED_TXN_REFUND_RESPONSE =
  """
  {
  "id": "%TX_ID%",
  "sep": "24",
  "kind": "withdrawal",
  "status": "refunded",
  "amount_expected": {
    "amount": "1",
    "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "amount_in": {
    "amount": "1",
    "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
  },
  "amount_out": {
    "amount": "99.5",
    "asset": "iso4217:USD"
  },
  "fee_details": {
    "total": "0.5",
    "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
  },
  "completed_at": "2023-08-08T14:07:28.799297Z",
  "message": "test message",
  "refunds": {
    "amount_refunded": {
      "amount": "0.0001",
      "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    },
    "amount_fee": {
      "amount": "0",
      "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    },
    "payments": [
      {
        "id": "%TESTPAYMENT_TXN_HASH%",
        "id_type": "stellar",
        "amount": {
          "amount": "%TESTPAYMENT_AMOUNT%",
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
      "memo": "testTag",
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
  "destination_account": "testAddress",
  "memo": "testTag",
  "memo_type": "id",
  "refund_memo": "12345",
  "refund_memo_type": "id",
  "client_name": "referenceCustodial"
}
"""

private const val REQUEST_OFFCHAIN_FUNDS_REQUEST =
  """{
    "transaction_id": "testTxId",
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

private const val NOTIFY_OFFCHAIN_FUNDS_RECEIVED_REQUEST =
  """{
    "transaction_id": "testTxId",
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
    "external_transaction_id": "1"
  }"""

private const val REQUEST_ONCHAIN_FUNDS_REQUEST =
  """
  {
    "transaction_id": "%TX_ID%",
    "message": "test message 1",
    "amount_in": {
      "amount": "1",
      "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    },
    "amount_out": {
      "amount": "99.5",
      "asset": "iso4217:USD"
    },
    "fee_details": {
      "total": "0.5",
      "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    },
    "amount_expected": {
      "amount": "1"
    }
  }
"""

private const val NOTIFY_ONCHAIN_FUNDS_RECEIVED_REQUEST =
  """
  {
    "transaction_id": "%TX_ID%",
    "message": "test message 1",
    "stellar_transaction_id": "%TESTPAYMENT_TXN_HASH%"
  }
"""

private const val DO_STELLAR_REFUND_REQUEST =
  """
  {
    "transaction_id": "%TX_ID%",
    "message": "test message",
    "refund": {
        "amount": {
            "amount": 1,
            "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        },
        "amount_fee": {
            "amount": 0,
            "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
        }
    }
  }
"""
