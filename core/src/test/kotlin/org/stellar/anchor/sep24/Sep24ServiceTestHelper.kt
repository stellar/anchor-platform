package org.stellar.anchor.sep24

import org.stellar.anchor.TestConstants
import org.stellar.anchor.TestConstants.Companion.TEST_ACCOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_AMOUNT
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET
import org.stellar.anchor.TestConstants.Companion.TEST_ASSET_ISSUER_ACCOUNT_ID
import org.stellar.anchor.TestConstants.Companion.TEST_OFFCHAIN_ASSET
import org.stellar.anchor.TestConstants.Companion.TEST_QUOTE_ID

fun createTestTransactionRequest(quoteID: String? = null): MutableMap<String, String> {
  val request =
    mutableMapOf(
      "lang" to "en",
      "asset_code" to TEST_ASSET,
      "asset_issuer" to TEST_ASSET_ISSUER_ACCOUNT_ID,
      "account" to TEST_ACCOUNT,
      "amount" to TEST_AMOUNT,
      "source_asset" to TEST_OFFCHAIN_ASSET,
      "destination_asset" to TEST_OFFCHAIN_ASSET,
      "email_address" to "jamie@stellar.org",
      "first_name" to "Jamie",
      "last_name" to "Li",
    )
  if (quoteID != null) {
    request["quote_id"] = quoteID
  }
  return request
}

fun createTestTransaction(kind: String): Sep24Transaction {
  val txn = PojoSep24Transaction()
  txn.transactionId = TestConstants.TEST_TRANSACTION_ID_0
  txn.status = "incomplete"
  txn.kind = kind
  txn.startedAt = Sep24ServiceTest.TEST_STARTED_AT
  txn.completedAt = Sep24ServiceTest.TEST_COMPLETED_AT

  txn.requestAssetCode = TEST_ASSET
  txn.requestAssetIssuer = TEST_ASSET_ISSUER_ACCOUNT_ID
  txn.sep10Account = TEST_ACCOUNT
  txn.toAccount = TEST_ACCOUNT
  txn.fromAccount = TEST_ACCOUNT
  txn.clientDomain = TestConstants.TEST_CLIENT_DOMAIN
  txn.protocol = "sep24"
  txn.amountIn = "321.4"
  txn.amountOut = "321.4"

  return txn
}

fun createTestTransactions(kind: String): MutableList<Sep24Transaction> {
  val txns = ArrayList<Sep24Transaction>()

  var txn = PojoSep24Transaction()
  txn.transactionId = TestConstants.TEST_TRANSACTION_ID_0
  txn.status = "incomplete"
  txn.kind = kind
  txn.startedAt = Sep24ServiceTest.TEST_STARTED_AT
  txn.completedAt = Sep24ServiceTest.TEST_COMPLETED_AT

  txn.requestAssetCode = TEST_ASSET
  txn.requestAssetIssuer = TEST_ASSET_ISSUER_ACCOUNT_ID
  txn.sep10Account = TEST_ACCOUNT
  txn.toAccount = TEST_ACCOUNT
  txn.fromAccount = TEST_ACCOUNT
  txn.clientDomain = TestConstants.TEST_CLIENT_DOMAIN
  txn.protocol = "sep24"
  txn.amountIn = "321.4"
  txn.amountOut = "321.4"
  txn.quoteId = TEST_QUOTE_ID
  txns.add(txn)

  txn = PojoSep24Transaction()
  txn.transactionId = TestConstants.TEST_TRANSACTION_ID_1
  txn.status = "completed"
  txn.kind = kind
  txn.startedAt = Sep24ServiceTest.TEST_STARTED_AT
  txn.completedAt = Sep24ServiceTest.TEST_COMPLETED_AT

  txn.requestAssetCode = TEST_ASSET
  txn.requestAssetIssuer = TEST_ASSET_ISSUER_ACCOUNT_ID
  txn.sep10Account = TEST_ACCOUNT
  txn.toAccount = TEST_ACCOUNT
  txn.fromAccount = TEST_ACCOUNT
  txn.clientDomain = TestConstants.TEST_CLIENT_DOMAIN
  txn.protocol = "sep24"
  txn.amountIn = "456.7"
  txn.amountOut = "456.7"
  txn.quoteId = TEST_QUOTE_ID
  txns.add(txn)

  return txns
}
