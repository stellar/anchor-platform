package org.stellar.anchor.platform.custody.fireblocks

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import java.time.Instant
import kotlin.test.assertEquals
import org.apache.commons.lang3.StringUtils
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasEntry
import org.junit.jupiter.api.*
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.exception.FireblocksException
import org.stellar.anchor.platform.config.FireblocksConfig
import org.stellar.anchor.platform.data.JdbcCustodyTransaction
import org.stellar.anchor.util.GsonUtils

class FireblocksPaymentServiceTest {

  companion object {
    private const val VAULT_ACCOUNT_ID = "testVaultAccountId"
    private const val ASSET_ID = "TEST_ASSET_ID"
    private const val TO_ACCOUNT_ID = "toVaultAccountId"
    private const val AMOUNT = "10"
    private const val EXTERNAL_TXN_ID = "TRANSACTION_ID"
  }

  private val gson = GsonUtils.getInstance()

  @MockK(relaxed = true) private lateinit var fireblocksClient: FireblocksApiClient
  @MockK(relaxed = true) private lateinit var fireblocksConfig: FireblocksConfig

  private lateinit var fireblocksPaymentService: FireblocksPaymentService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    every { fireblocksConfig.vaultAccountId } returns VAULT_ACCOUNT_ID

    fireblocksPaymentService = FireblocksPaymentService(fireblocksClient, fireblocksConfig)
  }

  @Test
  fun test_generateDepositAddress_success() {
    val requestCapture = slot<String>()
    val urlCapture = slot<String>()

    every { fireblocksClient.post(capture(urlCapture), capture(requestCapture)) } returns
      createNewDepositAddressResponse
    every { fireblocksConfig.getFireblocksAssetCode(ASSET_ID) } returns ASSET_ID

    val response = fireblocksPaymentService.generateDepositAddress(ASSET_ID)

    Assertions.assertEquals(
      "/v1/vault/accounts/testVaultAccountId/TEST_ASSET_ID/addresses",
      urlCapture.captured
    )
    JSONAssert.assertEquals(
      createNewDepositAddressRequest,
      requestCapture.captured,
      JSONCompareMode.STRICT
    )
    JSONAssert.assertEquals(
      generatedDepositAddressResponse,
      gson.toJson(response),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_createTransactionPayment_success() {
    val expectedResponse = "{\"id\":\"1\"}"
    val requestCapture = slot<String>()
    val urlCapture = slot<String>()

    every { fireblocksClient.post(capture(urlCapture), capture(requestCapture)) } returns
      createNewTransactionPaymentResponse
    every { fireblocksConfig.getFireblocksAssetCode(ASSET_ID) } returns ASSET_ID

    val transaction =
      JdbcCustodyTransaction.builder()
        .fromAccount(VAULT_ACCOUNT_ID)
        .toAccount(TO_ACCOUNT_ID)
        .asset(ASSET_ID)
        .amount(AMOUNT)
        .build()
    val response = fireblocksPaymentService.createTransactionPayment(transaction, StringUtils.EMPTY)

    Assertions.assertEquals("/v1/transactions", urlCapture.captured)

    JSONAssert.assertEquals(
      createNewTransactionPaymentRequest,
      requestCapture.captured,
      JSONCompareMode.STRICT
    )
    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)
  }

  @Test
  fun test_createTransactionPayment_failure() {
    val expectedResponse = "{\"id\":\"\"}"

    every { fireblocksClient.post(any(), any()) } returns errorResponse

    val transaction = JdbcCustodyTransaction()
    val response = fireblocksPaymentService.createTransactionPayment(transaction, StringUtils.EMPTY)

    JSONAssert.assertEquals(expectedResponse, gson.toJson(response), JSONCompareMode.STRICT)
  }

  @Test
  fun test_createTransactionPayment_IOException() {
    val expectedMessage =
      "Fireblocks API returned an error. HTTP status[503], response[Fireblocks service is unavailable]"

    every { fireblocksClient.post(any(), any()) } throws
      FireblocksException("Fireblocks service is unavailable", 503)

    val transaction = JdbcCustodyTransaction()
    val exception =
      assertThrows<FireblocksException> {
        fireblocksPaymentService.createTransactionPayment(transaction, StringUtils.EMPTY)
      }

    assertEquals(expectedMessage, exception.message)
  }

  @Test
  fun test_getTransactionById_success() {
    val urlCapture = slot<String>()

    every { fireblocksClient.get(capture(urlCapture)) } returns getTransactionResponse

    val response = fireblocksPaymentService.getTransactionById(EXTERNAL_TXN_ID)

    Assertions.assertEquals(
      String.format("/v1/transactions/%s", EXTERNAL_TXN_ID),
      urlCapture.captured
    )

    JSONAssert.assertEquals(getTransactionResponse, gson.toJson(response), JSONCompareMode.STRICT)
  }

  @Test
  fun test_getTransactionsByTimeRange_success() {
    val urlCapture = slot<String>()
    val queryParamsCapture = slot<Map<String, String>>()

    every { fireblocksClient.get(capture(urlCapture), capture(queryParamsCapture)) } returns
      twoTransactionsResponse

    val endTime = Instant.now()
    val startTime = endTime.minusSeconds(5)
    val response = fireblocksPaymentService.getTransactionsByTimeRange(startTime, endTime)

    Assertions.assertEquals("/v1/transactions", urlCapture.captured)
    Assertions.assertEquals(5, queryParamsCapture.captured.size)

    assertThat(queryParamsCapture.captured, hasEntry("after", startTime.toEpochMilli().toString()))
    assertThat(queryParamsCapture.captured, hasEntry("before", endTime.toEpochMilli().toString()))
    assertThat(queryParamsCapture.captured, hasEntry("limit", "500"))
    assertThat(queryParamsCapture.captured, hasEntry("orderBy", "createdAt"))
    assertThat(queryParamsCapture.captured, hasEntry("sort", "ASC"))

    JSONAssert.assertEquals(twoTransactionsResponse, gson.toJson(response), JSONCompareMode.STRICT)
  }

  @Test
  fun `getTransactionsByTimeRange select number of items equal to limit`() {
    fireblocksPaymentService = FireblocksPaymentService(fireblocksClient, fireblocksConfig, 2)
    val startTime = Instant.now().minusSeconds(5)
    val endTime = Instant.now()

    val queryParams =
      mapOf(
        "after" to startTime.toEpochMilli().toString(),
        "before" to endTime.toEpochMilli().toString(),
        "limit" to fireblocksPaymentService.transactionLimit.toString(),
        "orderBy" to "createdAt",
        "sort" to "ASC"
      )
    every { fireblocksClient.get(any(), queryParams) } returns twoTransactionsResponse

    val maxCreatedTime = 1684856015569L
    val secondQueryParams =
      mapOf(
        "after" to maxCreatedTime.toString(),
        "before" to endTime.toEpochMilli().toString(),
        "limit" to fireblocksPaymentService.transactionLimit.toString(),
        "orderBy" to "createdAt",
        "sort" to "ASC"
      )
    every { fireblocksClient.get(any(), secondQueryParams) } returns StringUtils.EMPTY

    val response = fireblocksPaymentService.getTransactionsByTimeRange(startTime, endTime)

    JSONAssert.assertEquals(twoTransactionsResponse, gson.toJson(response), JSONCompareMode.STRICT)
  }

  @Test
  fun `getTransactionsByTimeRange select more than limit`() {
    fireblocksPaymentService = FireblocksPaymentService(fireblocksClient, fireblocksConfig, 2)
    val startTime = Instant.now().minusSeconds(5)
    val endTime = Instant.now()

    val queryParams =
      mapOf(
        "after" to startTime.toEpochMilli().toString(),
        "before" to endTime.toEpochMilli().toString(),
        "limit" to fireblocksPaymentService.transactionLimit.toString(),
        "orderBy" to "createdAt",
        "sort" to "ASC"
      )
    every { fireblocksClient.get(any(), queryParams) } returns twoTransactionsResponse

    val maxCreatedTime = 1684856015569L
    val secondQueryParams =
      mapOf(
        "after" to maxCreatedTime.toString(),
        "before" to endTime.toEpochMilli().toString(),
        "limit" to fireblocksPaymentService.transactionLimit.toString(),
        "orderBy" to "createdAt",
        "sort" to "ASC"
      )
    every { fireblocksClient.get(any(), secondQueryParams) } returns oneTransactionResponse

    val response = fireblocksPaymentService.getTransactionsByTimeRange(startTime, endTime)
    assertEquals(3, response.size)
  }

  @Test
  fun `getTransactionsByTimeRange time range validation`() {
    val startTime = Instant.now().minusSeconds(5)
    val endTime = Instant.now()

    val ex =
      assertThrows<IllegalArgumentException> {
        fireblocksPaymentService.getTransactionsByTimeRange(endTime, startTime)
      }
    assertEquals("End time can't be before start time", ex.message)
  }

  private val createNewDepositAddressRequest = """
{
}
"""

  private val createNewDepositAddressResponse =
    """
{
  "address": "testAddress",
  "legacyAddress": "testLegacyAddress",
  "enterpriseAddress": "testEnterpriseAddress",
  "tag": "testTag",
  "bip44AddressIndex": "12345"
}
"""

  private val generatedDepositAddressResponse =
    """
{
  "address": "testAddress",
  "memo": "testTag",
  "memoType": "id"
}
"""

  private val createNewTransactionPaymentRequest =
    """
{
  "assetId": "TEST_ASSET_ID",
  "source": {
    "type": "VAULT_ACCOUNT",
    "id": "testVaultAccountId"
  },
  "destination": {
    "type": "ONE_TIME_ADDRESS",
    "oneTimeAddress": {
      "address": "toVaultAccountId"
    }
  },
  "amount": "10"
}
"""

  private val createNewTransactionPaymentResponse = """
{
  "id": "1",
  "status": "SUBMITTED"
}
"""

  private val errorResponse =
    """
{
  "id": "",
  "status": "FAILED",
  "systemMessages": [
    {
      "type": "WARN",
      "message": "message1"
    },
    {
      "type": "BLOCK",
      "message": "message2"
    }
  ]
}
"""

  private val getTransactionResponse =
    """
{
  "id": "2db60d66-163f-4caf-9cf4-538ce895f32f",
  "createdAt": 1683906114688,
  "lastUpdated": 1683906114958,
  "assetId": "XLM_USDC_T_CEKS",
  "source": {
    "id": "",
    "type": "UNKNOWN",
    "name": "External",
    "subType": ""
  },
  "destination": {
    "id": "1",
    "type": "VAULT_ACCOUNT",
    "name": "TestAnchor",
    "subType": ""
  },
  "amount": 0.2,
  "fee": 0.00001,
  "networkFee": 0.00001,
  "netAmount": 0.2,
  "sourceAddress": "GD3ZMOHJXZCFGZZSTFD7ZXFVSYEJUBFV4RQXJOQSLNSSQ46WNSESU2PS",
  "destinationAddress": "GB4LDT5G6GC26QUDS3YQ3XCIRSIYTIIWA3PLKFP4B7PRZC5DRZKSZHSK",
  "destinationAddressDescription": "",
  "destinationTag": "1222977634",
  "status": "COMPLETED",
  "txHash": "fc0023db2ddcedf1d9ee0c7b8da68eef043b8e6928d1ec74347c05038a2e584c",
  "subStatus": "CONFIRMED",
  "signedBy": [],
  "createdBy": "",
  "rejectedBy": "",
  "amountUSD": 0.2,
  "addressType": "",
  "note": "",
  "exchangeTxId": "",
  "requestedAmount": 0.2,
  "feeCurrency": "XLM_TEST",
  "operation": "TRANSFER",
  "numOfConfirmations": 1,
  "amountInfo": {
    "amount": "0.2",
    "requestedAmount": "0.2",
    "netAmount": "0.2",
    "amountUSD": "0.20"
  },
  "feeInfo": {
    "networkFee": "0.00001"
  },
  "destinations": [],
  "blockInfo": {
    "blockHeight": "957916",
    "blockHash": "09337b0c320b753bab7e884e660892990f73d5eed3e4ec07dbf85880d34eccb7"
  },
  "signedMessages": [],
  "index": 0
}
"""

  private val oneTransactionResponse =
    """
[
  {
    "id": "c12775b1-ed62-4998-9061-cfa7c6e38bbc",
    "createdAt": 1684856015023,
    "lastUpdated": 1684856424529,
    "assetId": "XLM_USDC_T_CEKS",
    "source": {
      "id": "1",
      "type": "VAULT_ACCOUNT",
      "name": "TestAnchor",
      "subType": ""
    },
    "destination": {
      "type": "ONE_TIME_ADDRESS",
      "name": "N/A",
      "subType": ""
    },
    "amount": 0.28,
    "fee": 0.00001,
    "networkFee": 0.00001,
    "netAmount": 0.28,
    "sourceAddress": "GB4LDT5G6GC26QUDS3YQ3XCIRSIYTIIWA3PLKFP4B7PRZC5DRZKSZHSK",
    "destinationAddress": "GBX3C62RHF6C7EVG4QXJ4PORIRSQLPBBOIDSKYRLK4H2JBTPTJZM4V6E",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "COMPLETED",
    "txHash": "916a7f9dd6e09b86256771b5df7be5f8f4ac09e1ddc6eb4423f0b500151e9367",
    "subStatus": "CONFIRMED",
    "signedBy": [
      "1444ed36-5bc0-4e3b-9b17-5df29fc0590f"
    ],
    "createdBy": "1444ed36-5bc0-4e3b-9b17-5df29fc0590f",
    "rejectedBy": "",
    "amountUSD": 0.28112,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 0.28,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "0.28",
      "requestedAmount": "0.28",
      "netAmount": "0.28",
      "amountUSD": "0.28112"
    },
    "feeInfo": {
      "networkFee": "0.00001"
    },
    "destinations": [],
    "blockInfo": {
      "blockHeight": "1138780",
      "blockHash": "5252168d088561d5b1ac6dedfa4d078384f2aff4d123add1cc58225770c2815d"
    },
    "signedMessages": []
  }
]
"""

  private val twoTransactionsResponse =
    """
[
  {
    "id": "83f2f9f1-e30f-4bd5-8db1-b51f0323870e",
    "createdAt": 1684856015569,
    "lastUpdated": 1684856489313,
    "assetId": "XLM_USDC_T_CEKS",
    "source": {
      "id": "1",
      "type": "VAULT_ACCOUNT",
      "name": "TestAnchor",
      "subType": ""
    },
    "destination": {
      "type": "ONE_TIME_ADDRESS",
      "name": "N/A",
      "subType": ""
    },
    "amount": 0.6,
    "fee": 0.0005,
    "networkFee": 0.0005,
    "netAmount": 0.6,
    "sourceAddress": "GB4LDT5G6GC26QUDS3YQ3XCIRSIYTIIWA3PLKFP4B7PRZC5DRZKSZHSK",
    "destinationAddress": "GBX3C62RHF6C7EVG4QXJ4PORIRSQLPBBOIDSKYRLK4H2JBTPTJZM4V6E",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "COMPLETED",
    "txHash": "d0bae55aba646131ce7fa4991a1a5fb467e8afc9f84836fa7e0994ae864f2cea",
    "subStatus": "CONFIRMED",
    "signedBy": [
      "1444ed36-5bc0-4e3b-9b17-5df29fc0590f"
    ],
    "createdBy": "1444ed36-5bc0-4e3b-9b17-5df29fc0590f",
    "rejectedBy": "",
    "amountUSD": 0.6024,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 0.6,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "0.6",
      "requestedAmount": "0.6",
      "netAmount": "0.6",
      "amountUSD": "0.6024"
    },
    "feeInfo": {
      "networkFee": "0.0005"
    },
    "destinations": [],
    "blockInfo": {
      "blockHeight": "1138792",
      "blockHash": "d73713270ad506554f1aadfd29d054a2d35ef16399a99230c5d1d4201c3169bd"
    },
    "signedMessages": []
  },
  {
    "id": "c12775b1-ed62-4998-9061-cfa7c6e38bbc",
    "createdAt": 1684856015023,
    "lastUpdated": 1684856424529,
    "assetId": "XLM_USDC_T_CEKS",
    "source": {
      "id": "1",
      "type": "VAULT_ACCOUNT",
      "name": "TestAnchor",
      "subType": ""
    },
    "destination": {
      "type": "ONE_TIME_ADDRESS",
      "name": "N/A",
      "subType": ""
    },
    "amount": 0.28,
    "fee": 0.00001,
    "networkFee": 0.00001,
    "netAmount": 0.28,
    "sourceAddress": "GB4LDT5G6GC26QUDS3YQ3XCIRSIYTIIWA3PLKFP4B7PRZC5DRZKSZHSK",
    "destinationAddress": "GBX3C62RHF6C7EVG4QXJ4PORIRSQLPBBOIDSKYRLK4H2JBTPTJZM4V6E",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "COMPLETED",
    "txHash": "916a7f9dd6e09b86256771b5df7be5f8f4ac09e1ddc6eb4423f0b500151e9367",
    "subStatus": "CONFIRMED",
    "signedBy": [
      "1444ed36-5bc0-4e3b-9b17-5df29fc0590f"
    ],
    "createdBy": "1444ed36-5bc0-4e3b-9b17-5df29fc0590f",
    "rejectedBy": "",
    "amountUSD": 0.28112,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 0.28,
    "feeCurrency": "XLM_TEST",
    "operation": "TRANSFER",
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "0.28",
      "requestedAmount": "0.28",
      "netAmount": "0.28",
      "amountUSD": "0.28112"
    },
    "feeInfo": {
      "networkFee": "0.00001"
    },
    "destinations": [],
    "blockInfo": {
      "blockHeight": "1138780",
      "blockHash": "5252168d088561d5b1ac6dedfa4d078384f2aff4d123add1cc58225770c2815d"
    },
    "signedMessages": []
  }
]
"""
}
