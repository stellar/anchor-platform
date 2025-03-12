package org.stellar.anchor.platform.integrationtest

import com.google.gson.reflect.TypeToken
import org.apache.hc.core5.http.HttpStatus.SC_OK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
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
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.client.*
import org.stellar.anchor.platform.CLIENT_WALLET_ACCOUNT
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.StringHelper.json
import org.stellar.walletsdk.asset.IssuedAssetId

// TODO add refund flow test for withdrawal: https://stellarorg.atlassian.net/browse/ANCHOR-694
class PlatformApiTests : PlatformAPITestBase(TestConfig()) {
  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
  }

  private val gson = GsonUtils.getInstance()

  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)
  private val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token.token)
  private val sep6Client = Sep6Client(toml.getString("TRANSFER_SERVER"), token.token)
  private val sep24Client = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), token.token)
  private val sep31Client = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), token.token)
  private val sep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), token.token)

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_onchain_funds_sent
   * 4. completed
   */
  @Test
  fun `SEP-6 deposit complete short flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES
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
  fun `SEP-6 deposit with pending-external status`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_WITH_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_WITH_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_onchain_funds_sent
   * 4. completed
   */
  @Test
  fun `SEP-6 deposit-exchange complete short flow`() {
    `test sep6 deposit-exchange flow`(
      SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES
    )
  }

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
  fun `SEP-6 deposit complete full with trust flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES
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
  fun `SEP-6 deposit complete full with recovery flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES
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
  fun `SEP-6 deposit complete short partial refund flow`() {
    `test sep6 deposit flow`(
      SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_sent
   * 4. completed
   */
  @Test
  fun `SEP-6 withdraw complete short flow`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_sent
   * 4. completed
   */
  @Test
  fun `SEP-6 withdraw-exchange complete short flow`() {
    `test sep6 withdraw-exchange flow`(
      SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES
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
  fun `SEP-6 withdraw complete full via pending external`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES
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
  fun `SEP-6 withdraw complete full via pending user`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES
    )
  }

  @Test
  fun `SEP-6 withdraw full refund`() {
    `test sep6 withdraw flow`(
      SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. incomplete -> request_offchain_funds
   * 2. pending_user_transfer_start -> notify_offchain_funds_received
   * 3. pending_anchor -> notify_onchain_funds_sent
   * 4. completed
   */
  @Test
  fun `SEP-24 deposit complete short flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES
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
  fun `SEP-24 deposit complete full with trust flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES
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
  fun `SEP-24 deposit complete full with recovery flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES
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
  fun `SEP-24 deposit complete short partial refund flow`() {
    `test sep24 deposit flow`(
      SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_offchain_funds_sent
   * 4. completed
   */
  @Test
  fun `SEP-24 withdraw complete short flow`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES
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
  fun `SEP-24 withdraw complete full via pending external`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES
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
  fun `SEP-24 withdraw complete full via pending user`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. incomplete -> request_onchain_funds
   * 2. pending_user_transfer_start -> notify_onchain_funds_received
   * 3. pending_anchor -> notify_refund_sent
   * 4. refunded
   */
  @Test
  fun `SEP-24 withdraw full refund`() {
    `test sep24 withdraw flow`(
      SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS,
      SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. pending_receiver -> request_onchain_funds
   * 2. pending_sender -> notify_onchain_funds_received
   * 3. pending_receiver -> notify_refund_sent
   * 4. refunded
   */
  @Test
  fun `SEP-31 refunded short`() {
    `test receive flow`(
      SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_REQUESTS,
      SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_RESPONSES
    )
  }

  /**
   * 1. pending_receiver -> request_onchain_funds
   * 2. pending_sender -> notify_onchain_funds_received
   * 3. pending_receiver -> notify_customer_info_updated
   * 4. pending_receiver -> notify_transaction_error
   * 5. error -> notify_transaction_recovery
   * 6. pending_receiver -> notify_offchain_funds_pending
   * 7. pending_external -> notify_offchain_funds_sent
   * 8. completed
   */
  @Test
  fun `SEP-31 complete full with recovery`() {
    `test receive flow`(
      SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS,
      SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES
    )
  }

  @Test
  fun `test get transaction by api and rpc`() {
    val depositRequest = gson.fromJson(SEP_24_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId = depositResponse.id

    val rpcTxn = platformApiClient.getTransactionByRpc(txId)
    JSONAssert.assertEquals(
      EXPECTED_GET_TRANSACTION_BY_RPC_RESPONSE.inject(TX_ID_KEY, txId).trimIndent(),
      gson.toJson(rpcTxn),
      CustomComparator(
        JSONCompareMode.STRICT,
        Customization("started_at") { _, _ -> true },
        Customization("updated_at") { _, _ -> true }
      )
    )

    val apiTxn = platformApiClient.getTransaction(txId)
    assertEquals(
      gson.toJson(apiTxn),
      gson.toJson(rpcTxn),
    )
  }

  @Test
  fun `get transactions by rpc`() {
    val depositRequest = gson.fromJson(SEP_24_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse1 = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId1 = depositResponse1.id
    val depositResponse2 = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId2 = depositResponse2.id

    val rpcTxns1 =
      platformApiClient.getTransactionsByRpc(
        TransactionsSeps.SEP_24,
        TransactionsOrderBy.CREATED_AT,
        Sort.Direction.DESC,
        null,
        5,
        0
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
        0
      )
    // txn1 and txn2 are both incomplete
    val rpcTxnIds = rpcTxns2.records.map { txn -> txn.id }
    assertFalse(rpcTxnIds.contains(txId1))
    assertFalse(rpcTxnIds.contains(txId2))
  }

  @Test
  fun `send single rpc request`() {
    val depositRequest = gson.fromJson(SEP_24_DEPOSIT_REQUEST, HashMap::class.java)

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
      EXPECTED_RPC_RESPONSE.inject(TX_ID_KEY, txId),
      response.body?.string()?.trimIndent(),
      CustomComparator(
        JSONCompareMode.STRICT,
        Customization("[*].result.started_at") { _, _ -> true },
        Customization("[*].result.updated_at") { _, _ -> true }
      )
    )

    val txResponse = platformApiClient.getTransaction(txId)
    assertEquals(SepTransactionStatus.PENDING_USR_TRANSFER_START, txResponse.status)
  }

  @Test
  fun `send batch of rpc requests`() {
    val depositRequest = gson.fromJson(SEP_24_DEPOSIT_REQUEST, HashMap::class.java)

    @Suppress("UNCHECKED_CAST")
    val depositResponse = sep24Client.deposit(depositRequest as HashMap<String, String>)
    val txId = depositResponse.id

    val requestOffchainFundsParams =
      gson.fromJson(REQUEST_OFFCHAIN_FUNDS_PARAMS, RequestOffchainFundsRequest::class.java)
    val notifyOffchainFundsReceivedParams =
      gson.fromJson(
        NOTIFY_OFFCHAIN_FUNDS_RECEIVED_PARAMS,
        NotifyOffchainFundsReceivedRequest::class.java
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
      EXPECTED_RPC_BATCH_RESPONSE.inject(TX_ID_KEY, txId),
      response.body?.string()?.trimIndent(),
      CustomComparator(
        JSONCompareMode.STRICT,
        Customization("[*].result.transfer_received_at") { _, _ -> true },
        Customization("[*].result.started_at") { _, _ -> true },
        Customization("[*].result.updated_at") { _, _ -> true }
      )
    )

    val txResponse = platformApiClient.getTransaction(txId)
    assertEquals(SepTransactionStatus.PENDING_ANCHOR, txResponse.status)
  }

  @Test
  fun `Test validations and errors`() {
    `test sep24 deposit flow`(VALIDATIONS_AND_ERRORS_REQUESTS, VALIDATIONS_AND_ERRORS_RESPONSES)
  }

  fun `test SEP38 post quote will result in the quote stored in the platform server`() {
    val quote = sep38Client.postQuote(USDC.sep38, "100", "iso4217:USD")
    val fetchedQuote = platformApiClient.getQuote(quote.id)
    assertEquals(quote.id, fetchedQuote.id)
    assertEquals(quote.price, fetchedQuote.price)
    JSONAssert.assertEquals(
      json(quote),
      json(fetchedQuote),
      CustomComparator(JSONCompareMode.LENIENT)
    )
  }

  private fun `test receive flow`(actionRequests: String, actionResponses: String) {
    val receiverCustomerRequest =
      GsonUtils.getInstance().fromJson(CUSTOMER_1, Sep12PutCustomerRequest::class.java)
    val receiverCustomer = sep12Client.putCustomer(receiverCustomerRequest)
    val senderCustomerRequest =
      GsonUtils.getInstance().fromJson(CUSTOMER_2, Sep12PutCustomerRequest::class.java)
    val senderCustomer = sep12Client.putCustomer(senderCustomerRequest)

    val receiveRequestJson =
      SEP_31_RECEIVE_FLOW_REQUEST.inject(RECEIVER_ID_KEY, receiverCustomer!!.id)
        .inject(SENDER_ID_KEY, senderCustomer!!.id)

    SEP_31_RECEIVE_FLOW_REQUEST.inject(RECEIVER_ID_KEY, receiverCustomer.id)
      .inject(SENDER_ID_KEY, senderCustomer.id)
    val receiveRequest = gson.fromJson(receiveRequestJson, Sep31PostTransactionRequest::class.java)
    val receiveResponse = sep31Client.postTransaction(receiveRequest)

    val updatedActionRequests =
      actionRequests
        .inject(RECEIVER_ID_KEY, receiverCustomer.id)
        .inject(SENDER_ID_KEY, senderCustomer.id)
    val updatedActionResponses =
      actionResponses
        .inject(RECEIVER_ID_KEY, receiverCustomer.id)
        .inject(SENDER_ID_KEY, senderCustomer.id)

    `test flow`(receiveResponse.id, updatedActionRequests, updatedActionResponses)
  }

  private fun `test sep6 withdraw flow`(actionRequests: String, actionResponse: String) {
    val withdrawRequest = gson.fromJson(SEP_6_WITHDRAW_FLOW_REQUEST, HashMap::class.java)

    val customer =
      sep12Client.putCustomer(
        Sep12PutCustomerRequest.builder().account(CLIENT_WALLET_ACCOUNT).build()
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
        Sep12PutCustomerRequest.builder().account(CLIENT_WALLET_ACCOUNT).build()
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
        Sep12PutCustomerRequest.builder().account(CLIENT_WALLET_ACCOUNT).build()
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
        Sep12PutCustomerRequest.builder().account(CLIENT_WALLET_ACCOUNT).build()
      )
    val updatedActionRequests = actionRequests.replace(CUSTOMER_ID_KEY, customer!!.id)

    @Suppress("UNCHECKED_CAST")
    val depositResponse =
      sep6Client.deposit(depositRequest as HashMap<String, String>, exchange = true)
    `test flow`(depositResponse.id, updatedActionRequests, actionResponse)
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

  private fun `test flow`(txId: String, actionRequests: String, actionResponses: String) {
    val rpcActionRequestsType = object : TypeToken<List<RpcRequest>>() {}.type
    val rpcActionRequests: List<RpcRequest> =
      gson.fromJson(actionRequests.inject(TX_ID_KEY, txId), rpcActionRequestsType)

    val rpcActionResponses = platformApiClient.sendRpcRequest(rpcActionRequests)

    val expectedResult = actionResponses.inject(TX_ID_KEY, txId)
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
        Customization("[*].result.stellar_transactions[*].memo") { _, _ -> true }
      )
    )
  }
}

private const val CUSTOMER_ID_KEY = "CUSTOMER_ID"

private val SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
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

private val SEP_6_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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

private val SEP_6_DEPOSIT_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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

private val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS =
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

private val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES =
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

private val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS =
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

private val SEP_6_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES =
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

private val SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS =
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

private val SEP_6_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES =
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

private val SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
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

private val SEP_6_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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

private val SEP_6_WITHDRAW_EXCHANGE_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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

private val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS =
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

private val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES =
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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

private val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS =
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

private val SEP_6_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES =
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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

private val SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS =
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

private val SEP_6_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES =
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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
              "memo_type": "hash",
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

private val SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
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

private val SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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

private val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_REQUESTS =
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

private val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_TRUST_FLOW_ACTION_RESPONSES =
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

private val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS =
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

private val SEP_24_DEPOSIT_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES =
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

private val SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_REQUESTS =
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

private val SEP_24_DEPOSIT_COMPLETE_SHORT_PARTIAL_REFUND_FLOW_ACTION_RESPONSES =
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

private val SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_REQUESTS =
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

private val SEP_24_WITHDRAW_COMPLETE_SHORT_FLOW_ACTION_RESPONSES =
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

private val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_REQUESTS =
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

private val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_EXTERNAL_FLOW_ACTION_RESPONSES =
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

private val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_RESPONSES =
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

private val SEP_24_WITHDRAW_COMPLETE_FULL_VIA_PENDING_USER_FLOW_ACTION_REQUESTS =
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

private val SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_REQUESTS =
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

private val SEP_24_WITHDRAW_FULL_REFUND_FLOW_ACTION_RESPONSES =
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

private val SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_REQUESTS =
  """
[
  {
    "id": "1",
    "method": "request_onchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "memo": "MjJkMmM1MjEtMmQ4MS00ZmIxLWE0ZGItZjhjMDdiZjg",
      "memo_type": "hash"
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

private val SEP_31_RECEIVE_REFUNDED_SHORT_FLOW_ACTION_RESPONSES =
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
              "destination_account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
              "memo": "ZWQ0NmIwMzAtM2E5NC00M2RkLThkMWYtYWUwMjNhMGI=",
              "memo_type": "hash",
              "refund_memo": "MjJkMmM1MjEtMmQ4MS00ZmIxLWE0ZGItZjhjMDdiZjg",
              "refund_memo_type": "hash",
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

private val SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_REQUESTS =
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
    "id": "3",
    "method": "notify_customer_info_updated",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 3"
    }
  },
  {
    "id": "4",
    "method": "notify_transaction_error",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 4"
    }
  },
  {
    "id": "5",
    "method": "notify_transaction_recovery",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 5"
    }
  },
  {
    "id": "6",
    "method": "notify_offchain_funds_pending",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 6",
      "external_transaction_id": "ext123456789"
    }
  },
  {
    "id": "7",
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

private val SEP_31_RECEIVE_COMPLETE_FULL_WITH_RECOVERY_FLOW_ACTION_RESPONSES =
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
            "id": "3"
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
            "id": "4"
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
            "id": "5"
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
            "id": "6"
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
            "id": "7"
          }
        ]
      """

private val VALIDATIONS_AND_ERRORS_REQUESTS =
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

private val VALIDATIONS_AND_ERRORS_RESPONSES =
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
                "amount": "1.0000000",
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

private val SEP_6_WITHDRAW_FLOW_REQUEST =
  """
        {
          "asset_code": "USDC",
          "type": "bank_account",
          "amount": "1"
        }
      """

private val SEP_6_WITHDRAW_EXCHANGE_FLOW_REQUEST =
  """
        {
          "destination_asset": "iso4217:USD",
          "source_asset": "USDC",
          "amount": "1",
          "type": "bank_account"
        }
      """

private val SEP_6_DEPOSIT_FLOW_REQUEST =
  """
        {
          "asset_code": "USDC",
          "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
          "amount": "1",
          "type": "SWIFT"
        }
      """

private val SEP_6_DEPOSIT_EXCHANGE_FLOW_REQUEST =
  """
        {
          "destination_asset": "USDC",
          "source_asset": "iso4217:USD",
          "amount": "1",
          "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
          "type": "SWIFT"
        }
      """

private val SEP_24_DEPOSIT_FLOW_REQUEST = """
{
  "asset_code": "USDC"
}
  """

private val SEP_24_WITHDRAW_FLOW_REQUEST =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
    "account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
    "lang": "en"
}"""

private val SEP_31_RECEIVE_FLOW_REQUEST =
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

private val SEP_24_DEPOSIT_REQUEST =
  """{
    "asset_code": "USDC",
    "asset_issuer": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
    "lang": "en"
  }"""

private val REQUEST_OFFCHAIN_FUNDS_PARAMS =
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

private val NOTIFY_OFFCHAIN_FUNDS_RECEIVED_PARAMS =
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

private val EXPECTED_RPC_RESPONSE =
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

private val EXPECTED_RPC_BATCH_RESPONSE =
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

private val CUSTOMER_1 =
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

private val CUSTOMER_2 =
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
