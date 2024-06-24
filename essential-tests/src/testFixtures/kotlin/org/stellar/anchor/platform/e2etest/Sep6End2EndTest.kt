package org.stellar.anchor.platform.e2etest

import io.ktor.http.*
import kotlin.test.DefaultAsserter.fail
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.sep.sep6.GetTransactionResponse
import org.stellar.anchor.api.shared.InstructionField
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.Log
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.walletsdk.anchor.MemoType
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.anchor.customer
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.horizon.sign

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class Sep6End2EndTest : AbstractIntegrationTests(TestConfig()) {
  private val maxTries = 30
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))

  companion object {
    private val USDC =
      IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
    private val basicInfoFields = listOf("first_name", "last_name", "email_address")
    private val customerInfo =
      mapOf(
        "first_name" to "John",
        "last_name" to "Doe",
        "address" to "123 Bay Street",
        "email_address" to "john@email.com",
        "birth_date" to "1990-01-01",
        "id_type" to "drivers_license",
        "id_country_code" to "CAN",
        "id_issue_date" to "2023-01-01",
        "id_expiration_date" to "2099-01-01",
        "id_number" to "1234567890",
        "bank_account_number" to "13719713158835300",
        "bank_account_type" to "checking",
        "bank_number" to "123",
        "bank_branch_number" to "121122676",
      )
  }

  @Test
  fun `test typical deposit end-to-end flow`() = runBlocking {
    val memo = (10000..20000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    // TODO: migrate this to wallet-sdk when it's available
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    anchor.customer(token).add(basicInfoFields.associateWith { customerInfo[it]!! }, memo)

    val deposit =
      sep6Client.deposit(
        mapOf(
          "asset_code" to USDC.code,
          "account" to walletKeyPair.address,
          "amount" to "1",
          "type" to "SWIFT",
        )
      )
    Log.info("Deposit initiated: ${deposit.id}")
    waitStatus(deposit.id, PENDING_CUSTOMER_INFO_UPDATE, sep6Client)

    // Supply missing KYC info to continue with the transaction
    val additionalRequiredFields =
      anchor
        .customer(token)
        .get(transactionId = deposit.id)
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    anchor.customer(token).add(additionalRequiredFields.associateWith { customerInfo[it]!! }, memo)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")
    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, sep6Client)

    val completedDepositTxn = sep6Client.getTransaction(mapOf("id" to deposit.id))
    assertEquals(
      mapOf(
        "organization.bank_number" to
          InstructionField.builder()
            .value("121122676")
            .description("US Bank routing number")
            .build(),
        "organization.bank_account_number" to
          InstructionField.builder()
            .value("13719713158835300")
            .description("US Bank account number")
            .build(),
      ),
      completedDepositTxn.transaction.instructions,
    )
    val transactionByStellarId: GetTransactionResponse =
      sep6Client.getTransaction(
        mapOf("stellar_transaction_id" to completedDepositTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedDepositTxn.transaction.id, transactionByStellarId.transaction.id)

    val expectedStatuses =
      listOf(
        INCOMPLETE,
        PENDING_CUSTOMER_INFO_UPDATE, // request KYC
        PENDING_USR_TRANSFER_START, // provide deposit instructions
        PENDING_ANCHOR, // deposit into user wallet
        PENDING_STELLAR,
        COMPLETED,
      )
    assertWalletReceivedStatuses(deposit.id, expectedStatuses)
  }

  @Test
  fun `test typical deposit-exchange without quote end-to-end flow`() = runBlocking {
    val memo = (20000..30000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    anchor.customer(token).add(basicInfoFields.associateWith { customerInfo[it]!! }, memo)

    val deposit =
      sep6Client.deposit(
        mapOf(
          "destination_asset" to USDC.code,
          "source_asset" to "iso4217:CAD",
          "amount" to "1",
          "account" to walletKeyPair.address,
          "type" to "SWIFT",
        ),
        exchange = true,
      )
    Log.info("Deposit initiated: ${deposit.id}")
    waitStatus(deposit.id, PENDING_CUSTOMER_INFO_UPDATE, sep6Client)

    // Supply missing KYC info to continue with the transaction
    val additionalRequiredFields =
      anchor
        .customer(token)
        .get(transactionId = deposit.id)
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    anchor.customer(token).add(additionalRequiredFields.associateWith { customerInfo[it]!! }, memo)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")
    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, sep6Client)

    val completedDepositTxn = sep6Client.getTransaction(mapOf("id" to deposit.id))
    assertEquals(
      mapOf(
        "organization.bank_number" to
          InstructionField.builder()
            .value("121122676")
            .description("CA Bank routing number")
            .build(),
        "organization.bank_account_number" to
          InstructionField.builder()
            .value("13719713158835300")
            .description("CA Bank account number")
            .build(),
      ),
      completedDepositTxn.transaction.instructions,
    )
    val transactionByStellarId: GetTransactionResponse =
      sep6Client.getTransaction(
        mapOf("stellar_transaction_id" to completedDepositTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedDepositTxn.transaction.id, transactionByStellarId.transaction.id)

    val expectedStatuses =
      listOf(
        INCOMPLETE,
        PENDING_CUSTOMER_INFO_UPDATE, // request KYC
        PENDING_USR_TRANSFER_START, // provide deposit instructions
        PENDING_ANCHOR, // deposit into user wallet
        PENDING_STELLAR,
        COMPLETED,
      )
    assertWalletReceivedStatuses(deposit.id, expectedStatuses)
  }

  @Test
  fun `test typical withdraw end-to-end flow`() = runBlocking {
    val memo = (40000..50000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    // TODO: migrate this to wallet-sdk when it's available
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    anchor.customer(token).add(basicInfoFields.associateWith { customerInfo[it]!! }, memo)

    val withdraw =
      sep6Client.withdraw(
        mapOf("asset_code" to USDC.code, "amount" to "1", "type" to "bank_account")
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")
    waitStatus(withdraw.id, PENDING_CUSTOMER_INFO_UPDATE, sep6Client)

    // Supply missing financial account info to continue with the transaction
    val additionalRequiredFields =
      anchor
        .customer(token)
        .get(transactionId = withdraw.id)
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    anchor.customer(token).add(additionalRequiredFields.associateWith { customerInfo[it]!! }, memo)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, sep6Client)

    val withdrawTxn = sep6Client.getTransaction(mapOf("id" to withdraw.id)).transaction

    // Transfer the withdrawal amount to the Anchor
    Log.info("Transferring 1 USDC to Anchor account: ${withdrawTxn.withdrawAnchorAccount}")
    transactionWithRetry {
      val transfer =
        wallet
          .stellar()
          .transaction(walletKeyPair, memo = Pair(MemoType.HASH, withdrawTxn.withdrawMemo))
          .transfer(withdrawTxn.withdrawAnchorAccount, USDC, "1")
          .build()
      transfer.sign(walletKeyPair)
      wallet.stellar().submitTransaction(transfer)
    }
    Log.info("Transfer complete")
    waitStatus(withdraw.id, COMPLETED, sep6Client)

    val expectedStatuses =
      listOf(
        INCOMPLETE,
        PENDING_CUSTOMER_INFO_UPDATE, // request KYC
        PENDING_USR_TRANSFER_START, // wait for onchain user transfer
        PENDING_ANCHOR, // funds available for pickup
        PENDING_EXTERNAL,
        COMPLETED,
      )
    assertWalletReceivedStatuses(withdraw.id, expectedStatuses)
  }

  @Test
  fun `test typical withdraw-exchange without quote end-to-end flow`() = runBlocking {
    val memo = (50000..60000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    // TODO: migrate this to wallet-sdk when it's available
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    anchor.customer(token).add(basicInfoFields.associateWith { customerInfo[it]!! }, memo)

    val withdraw =
      sep6Client.withdraw(
        mapOf(
          "destination_asset" to "iso4217:CAD",
          "source_asset" to USDC.code,
          "amount" to "1",
          "type" to "bank_account",
        ),
        exchange = true,
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")
    waitStatus(withdraw.id, PENDING_CUSTOMER_INFO_UPDATE, sep6Client)

    // Supply missing financial account info to continue with the transaction
    val additionalRequiredFields =
      anchor
        .customer(token)
        .get(transactionId = withdraw.id)
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    anchor.customer(token).add(additionalRequiredFields.associateWith { customerInfo[it]!! }, memo)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, sep6Client)

    val withdrawTxn = sep6Client.getTransaction(mapOf("id" to withdraw.id)).transaction

    // Transfer the withdrawal amount to the Anchor
    Log.info("Transferring 1 USDC to Anchor account: ${withdrawTxn.withdrawAnchorAccount}")
    transactionWithRetry {
      val transfer =
        wallet
          .stellar()
          .transaction(walletKeyPair, memo = Pair(MemoType.HASH, withdrawTxn.withdrawMemo))
          .transfer(withdrawTxn.withdrawAnchorAccount, USDC, "1")
          .build()
      transfer.sign(walletKeyPair)
      wallet.stellar().submitTransaction(transfer)
    }
    Log.info("Transfer complete")
    waitStatus(withdraw.id, COMPLETED, sep6Client)

    val expectedStatuses =
      listOf(
        INCOMPLETE,
        PENDING_CUSTOMER_INFO_UPDATE, // request KYC
        PENDING_USR_TRANSFER_START, // wait for onchain user transfer
        PENDING_ANCHOR, // funds available for pickup
        PENDING_EXTERNAL,
        COMPLETED,
      )
    assertWalletReceivedStatuses(withdraw.id, expectedStatuses)
  }

  private suspend fun assertWalletReceivedStatuses(
    txnId: String,
    expected: List<SepTransactionStatus>,
  ) {
    val callbacks =
      walletServerClient.pollCallbacks(txnId, expected.size, GetTransactionResponse::class.java)
    val statuses = callbacks.map { it.transaction.status }
    assertContentEquals(expected.map { it.status }, statuses)
  }

  private suspend fun waitStatus(
    id: String,
    expectedStatus: SepTransactionStatus,
    sep6Client: Sep6Client,
  ) {
    var status: String? = null
    for (i in 0..maxTries) {
      val transaction = sep6Client.getTransaction(mapOf("id" to id))
      if (!status.equals(transaction.transaction.status)) {
        status = transaction.transaction.status
        Log.info(
          "Transaction(${transaction.transaction.id}) status changed to ${status}. Message: ${transaction.transaction.message}"
        )
      }
      if (transaction.transaction.status == expectedStatus.status) {
        return
      }
      delay(1.seconds)
    }
    fail("Transaction status $status did not match expected status $expectedStatus")
  }
}
