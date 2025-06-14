package org.stellar.anchor.sep6

import com.google.gson.Gson
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.MoreInfoUrlConstructor
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET_ISSUER_ACCOUNT_ID
import org.stellar.anchor.TestConstants.Companion.TEST_MEMO
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.util.GsonUtils

class Sep6TransactionUtilsTest {
  companion object {
    val gson: Gson = GsonUtils.getInstance()
  }

  @MockK(relaxed = true) lateinit var sep6MoreInfoUrlConstructor: MoreInfoUrlConstructor

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { sep6MoreInfoUrlConstructor.construct(any(), any()) } returns
      "https://example.com/more_info"
  }

  private val apiTxn =
    """
      {
          "id": "database-id",
          "kind": "deposit",
          "status": "pending_external",
          "status_eta": 100,
          "more_info_url": "https://example.com/more_info",
          "amount_in": "100.00",
          "amount_in_asset": "USD",
          "amount_out": "99.00",
          "amount_out_asset": "$TEST_ASSET",
          "fee_details": {
            "total": "1.00",
            "asset": "USD"
          },
          "quote_id": "quote-id",
          "from": "1234",
          "to": "$TEST_ASSET_ISSUER_ACCOUNT_ID",
          "deposit_memo_type": "text",
          "started_at": "1970-01-01T00:00:00.001Z",
          "updated_at": "1970-01-01T00:00:00.003Z",
          "completed_at": "1970-01-01T00:00:00.002Z",
          "stellar_transaction_id": "stellar-id",
          "external_transaction_id": "external-id",
          "message": "some message",
          "refunds": {
              "amount_refunded": {
                  "amount": "100.00",
                  "asset": "USD"
              },
              "amount_fee": {
                  "amount": "0",
                  "asset": "USD"
              },
              "payments": [
                  {
                      "id": "refund-payment-1-id",
                      "id_type": "external",
                      "amount": {
                          "amount": "50.00",
                          "asset": "USD"
                      },
                      "fee": {
                          "amount": "0",
                          "asset": "USD"
                      }
                  },
                  {
                      "id": "refund-payment-2-id",
                      "id_type": "external",
                      "amount": {
                          "amount": "50.00",
                          "asset": "USD"
                      },
                      "fee": {
                          "amount": "0",
                          "asset": "USD"
                      }
                  }
              ]
          },
          "required_info_message": "need more info",
          "required_info_updates": [
              "some_field"
          ],
          "instructions": {
              "key": {
                  "value": "1234",
                  "description": "Bank account number"
              }
          }
      }
    """
      .trimIndent()

  @Test
  fun `test fromTxn`() {
    val databaseTxn =
      PojoSep6Transaction().apply {
        id = "database-id"
        stellarTransactions =
          listOf(
            StellarTransaction.builder()
              .id("stellar-id")
              .memo("some memo")
              .memoType("text")
              .createdAt(Instant.ofEpochMilli(2))
              .envelope("some envelope")
              .payments(
                listOf(
                  StellarPayment.builder()
                    .id(UUID.randomUUID().toString())
                    .amount(Amount("100.0", TEST_ASSET))
                    .paymentType(StellarPayment.Type.PAYMENT)
                    .sourceAccount(TEST_ASSET_ISSUER_ACCOUNT_ID)
                    .destinationAccount(TEST_ACCOUNT)
                    .build()
                )
              )
              .build()
          )
        transactionId = "database-id"
        stellarTransactionId = "stellar-id"
        externalTransactionId = "external-id"
        status = "pending_external"
        statusEta = 100L
        kind = "deposit"
        startedAt = Instant.ofEpochMilli(1)
        completedAt = Instant.ofEpochMilli(2)
        updatedAt = Instant.ofEpochMilli(3)
        type = "bank_account"
        requestAssetCode = TEST_ASSET
        requestAssetIssuer = TEST_ASSET_ISSUER_ACCOUNT_ID
        amountIn = "100.00"
        amountInAsset = "USD"
        amountOut = "99.00"
        amountOutAsset = "USDC"
        feeDetails = FeeDetails("1.00", "USD", null)
        amountExpected = "100.00"
        webAuthAccount = TEST_ACCOUNT
        webAuthAccountMemo = TEST_MEMO
        withdrawAnchorAccount = TEST_ASSET_ISSUER_ACCOUNT_ID
        fromAccount = "1234"
        toAccount = TEST_ASSET_ISSUER_ACCOUNT_ID
        memoType = "text memo"
        memoType = "text"
        quoteId = "quote-id"
        message = "some message"
        refunds =
          Refunds().apply {
            amountRefunded = Amount("100.00", "USD")
            amountFee = Amount("0", "USD")
            payments =
              arrayOf(
                RefundPayment.builder()
                  .id("refund-payment-1-id")
                  .idType(RefundPayment.IdType.EXTERNAL)
                  .amount(Amount("50.00", "USD"))
                  .fee(Amount("0", "USD"))
                  .requestedAt(Instant.ofEpochMilli(1))
                  .refundedAt(Instant.ofEpochMilli(2))
                  .build(),
                RefundPayment.builder()
                  .id("refund-payment-2-id")
                  .idType(RefundPayment.IdType.EXTERNAL)
                  .amount(Amount("50.00", "USD"))
                  .fee(Amount("0", "USD"))
                  .requestedAt(Instant.ofEpochMilli(1))
                  .refundedAt(Instant.ofEpochMilli(3))
                  .build(),
              )
          }
        refundMemo = "some refund memo"
        refundMemoType = "text"
        requiredInfoMessage = "need more info"
        requiredInfoUpdates = listOf("some_field")
        instructions = mapOf("key" to InstructionField("1234", "Bank account number"))
      }

    JSONAssert.assertEquals(
      apiTxn,
      gson.toJson(Sep6TransactionUtils.fromTxn(databaseTxn, sep6MoreInfoUrlConstructor, null)),
      JSONCompareMode.STRICT,
    )
  }
}
