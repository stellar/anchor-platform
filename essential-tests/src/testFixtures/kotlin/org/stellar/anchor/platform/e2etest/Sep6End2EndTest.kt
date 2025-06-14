package org.stellar.anchor.platform.e2etest

import io.ktor.http.*
import kotlin.test.DefaultAsserter.fail
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.sep.sep6.GetTransactionResponse
import org.stellar.anchor.api.shared.InstructionField
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.*
import org.stellar.anchor.platform.TestSecrets.CLIENT_SMART_WALLET_ACCOUNT
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.customer
import org.stellar.walletsdk.asset.IssuedAssetId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class Sep6End2EndTest : IntegrationTestBase(TestConfig()) {
  private val maxTries = 30
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))
  private val gson = GsonUtils.getInstance()
  private val clientWalletAccount = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId

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
  fun `test classic asset deposit end-to-end flow`() = runBlocking {
    val memo = (10000..20000).random().toULong()
    val wallet = WalletClient(clientWalletAccount, CLIENT_WALLET_SECRET, memo.toString(), toml)

    // Create a customer before starting the transaction
    val customerRequest =
      gson
        .fromJson(
          gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
          Sep12PutCustomerRequest::class.java,
        )
        .also {
          it.memo = memo.toString()
          it.memoType = "id"
        }
    val customer = wallet.sep12.putCustomer(customerRequest)!!

    val deposit =
      wallet.sep6.deposit(
        mapOf(
          "asset_code" to USDC.code,
          "account" to clientWalletAccount,
          "amount" to "1",
          "type" to "SWIFT",
        )
      )
    Log.info("Deposit initiated: ${deposit.id}")
    waitStatus(deposit.id, PENDING_CUSTOMER_INFO_UPDATE, wallet.sep6)

    // Supply missing KYC info to continue with the transaction
    val additionalRequiredFields =
      anchor
        .customer(token)
        .get(transactionId = deposit.id)
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    wallet.sep12.putCustomer(
      gson
        .fromJson(
          gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
          Sep12PutCustomerRequest::class.java,
        )
        .also {
          it.memo = memo.toString()
          it.memoType = "id"
        }
    )
    Log.info("Submitted additional KYC info: $additionalRequiredFields")
    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, wallet.sep6)

    val completedDepositTxn = wallet.sep6.getTransaction(mapOf("id" to deposit.id))
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
      wallet.sep6.getTransaction(
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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer.id, expectedCustomerStatuses)
  }

  @Test
  fun `test typical deposit to contract account end-to-end`() = runBlocking {
    val wallet = WalletClient(CLIENT_SMART_WALLET_ACCOUNT, CLIENT_WALLET_SECRET, null, toml)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = wallet.sep12.putCustomer(customerRequest)!!

    val deposit =
      wallet.sep6.deposit(
        mapOf(
          "asset_code" to USDC.code,
          "account" to CLIENT_SMART_WALLET_ACCOUNT,
          "amount" to "10",
          "type" to "SWIFT",
        )
      )
    Log.info("Deposit initiated: ${deposit.id}")

    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(customer.id, "sep-6", deposit.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    val additionalCustomerRequest =
      gson.fromJson(
        gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    wallet.sep12.putCustomer(additionalCustomerRequest)

    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, wallet.sep6)

    val completedDepositTxn = wallet.sep6.getTransaction(mapOf("id" to deposit.id))
    val transactionByStellarId: GetTransactionResponse =
      wallet.sep6.getTransaction(
        mapOf("stellar_transaction_id" to completedDepositTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedDepositTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  @Test
  fun `test classic asset deposit-exchange without quote end-to-end flow`() = runBlocking {
    val memo = (20000..30000).random().toULong()
    val wallet = WalletClient(clientWalletAccount, CLIENT_WALLET_SECRET, memo.toString(), toml)

    // Create a customer before starting the transaction
    val customer =
      wallet.sep12.putCustomer(
        gson
          .fromJson(
            gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
            Sep12PutCustomerRequest::class.java,
          )
          .also {
            it.memo = memo.toString()
            it.memoType = "id"
          }
      )

    val deposit =
      wallet.sep6.deposit(
        mapOf(
          "destination_asset" to USDC.code,
          "source_asset" to "iso4217:CAD",
          "amount" to "1",
          "account" to clientWalletAccount,
          "type" to "SWIFT",
        ),
        exchange = true,
      )
    Log.info("Deposit initiated: ${deposit.id}")
    waitStatus(deposit.id, PENDING_CUSTOMER_INFO_UPDATE, wallet.sep6)

    // Supply missing KYC info to continue with the transaction
    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(transactionId = deposit.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    wallet.sep12.putCustomer(
      gson
        .fromJson(
          gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
          Sep12PutCustomerRequest::class.java,
        )
        .also {
          it.memo = memo.toString()
          it.memoType = "id"
        }
    )
    Log.info("Submitted additional KYC info: $additionalRequiredFields")
    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, wallet.sep6)

    val completedDepositTxn = wallet.sep6.getTransaction(mapOf("id" to deposit.id))
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
      wallet.sep6.getTransaction(
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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer!!.id, expectedCustomerStatuses)
  }

  @Test
  fun `test typical deposit-exchange to contract account end-to-end`() = runBlocking {
    val wallet = WalletClient(CLIENT_SMART_WALLET_ACCOUNT, CLIENT_WALLET_SECRET, null, toml)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = wallet.sep12.putCustomer(customerRequest)!!

    val deposit =
      wallet.sep6.deposit(
        mapOf(
          "destination_asset" to USDC.code,
          "source_asset" to "iso4217:CAD",
          "amount" to "1",
          "account" to CLIENT_SMART_WALLET_ACCOUNT,
          "type" to "SWIFT",
        ),
        exchange = true,
      )
    Log.info("Deposit initiated: ${deposit.id}")

    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(customer.id, "sep-6", deposit.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    val additionalCustomerRequest =
      gson.fromJson(
        gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    wallet.sep12.putCustomer(additionalCustomerRequest)

    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, wallet.sep6)

    val completedDepositTxn = wallet.sep6.getTransaction(mapOf("id" to deposit.id))
    val transactionByStellarId: GetTransactionResponse =
      wallet.sep6.getTransaction(
        mapOf("stellar_transaction_id" to completedDepositTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedDepositTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  @Test
  fun `test classic asset withdraw end-to-end flow`() = runBlocking {
    val memo = (40000..50000).random().toULong()
    val wallet = WalletClient(clientWalletAccount, CLIENT_WALLET_SECRET, memo.toString(), toml)

    // Create a customer before starting the transaction
    val customer =
      wallet.sep12.putCustomer(
        gson
          .fromJson(
            gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
            Sep12PutCustomerRequest::class.java,
          )
          .also {
            it.memo = memo.toString()
            it.memoType = "id"
          }
      )

    val withdraw =
      wallet.sep6.withdraw(
        mapOf("asset_code" to USDC.code, "amount" to "1", "type" to "bank_account")
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")
    waitStatus(withdraw.id, PENDING_CUSTOMER_INFO_UPDATE, wallet.sep6)

    // Supply missing financial account info to continue with the transaction
    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(transactionId = withdraw.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    wallet.sep12.putCustomer(
      gson
        .fromJson(
          gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
          Sep12PutCustomerRequest::class.java,
        )
        .also {
          it.memo = memo.toString()
          it.memoType = "id"
        }
    )
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, wallet.sep6)

    val withdrawTxn = wallet.sep6.getTransaction(mapOf("id" to withdraw.id)).transaction

    // Transfer the withdrawal amount to the Anchor
    Log.info("Transferring 1 USDC to Anchor account: ${withdrawTxn.withdrawAnchorAccount}")
    transactionWithRetry {
      wallet.send(
        withdrawTxn.withdrawAnchorAccount,
        Asset.create(USDC.id),
        "1",
        withdrawTxn.withdrawMemo,
        withdrawTxn.withdrawMemoType,
      )
    }
    Log.info("Transfer complete")
    waitStatus(withdraw.id, COMPLETED, wallet.sep6)

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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer!!.id, expectedCustomerStatuses)
  }

  @Test
  fun `test typical withdraw to contract account end-to-end`() = runBlocking {
    val wallet = WalletClient(CLIENT_SMART_WALLET_ACCOUNT, CLIENT_WALLET_SECRET, null, toml)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = wallet.sep12.putCustomer(customerRequest)!!

    val withdraw =
      wallet.sep6.withdraw(
        mapOf("asset_code" to USDC.code, "amount" to "1", "type" to "bank_account")
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")

    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(customer.id, "sep-6", withdraw.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    val additionalCustomerRequest =
      gson.fromJson(
        gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    wallet.sep12.putCustomer(additionalCustomerRequest)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, wallet.sep6)

    val withdrawTxn = wallet.sep6.getTransaction(mapOf("id" to withdraw.id)).transaction

    transactionWithRetry {
      val txHash =
        wallet.send(
          withdrawTxn.withdrawAnchorAccount,
          Asset.create(USDC.id),
          "1",
          withdrawTxn.withdrawMemo,
          withdrawTxn.withdrawMemoType,
        )
      Log.info("Transfer complete: $txHash")
    }

    waitStatus(withdraw.id, COMPLETED, wallet.sep6)

    val completedWithdrawTxn = wallet.sep6.getTransaction(mapOf("id" to withdraw.id))
    val transactionByStellarId: GetTransactionResponse =
      wallet.sep6.getTransaction(
        mapOf("stellar_transaction_id" to completedWithdrawTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedWithdrawTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  @Test
  fun `test classic asset withdraw-exchange without quote end-to-end flow`() = runBlocking {
    val memo = (50000..60000).random().toULong()
    val wallet = WalletClient(clientWalletAccount, CLIENT_WALLET_SECRET, memo.toString(), toml)

    // Create a customer before starting the transaction
    val customer =
      wallet.sep12.putCustomer(
        gson
          .fromJson(
            gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
            Sep12PutCustomerRequest::class.java,
          )
          .also {
            it.memo = memo.toString()
            it.memoType = "id"
          }
      )

    val withdraw =
      wallet.sep6.withdraw(
        mapOf(
          "destination_asset" to "iso4217:CAD",
          "source_asset" to USDC.code,
          "amount" to "1",
          "type" to "bank_account",
        ),
        exchange = true,
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")
    waitStatus(withdraw.id, PENDING_CUSTOMER_INFO_UPDATE, wallet.sep6)

    // Supply missing financial account info to continue with the transaction
    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(transactionId = withdraw.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    wallet.sep12.putCustomer(
      gson
        .fromJson(
          gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
          Sep12PutCustomerRequest::class.java,
        )
        .also {
          it.memo = memo.toString()
          it.memoType = "id"
        }
    )
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, wallet.sep6)

    val withdrawTxn = wallet.sep6.getTransaction(mapOf("id" to withdraw.id)).transaction

    // Transfer the withdrawal amount to the Anchor
    Log.info("Transferring 1 USDC to Anchor account: ${withdrawTxn.withdrawAnchorAccount}")
    transactionWithRetry {
      wallet.send(
        withdrawTxn.withdrawAnchorAccount,
        Asset.create(USDC.id),
        "1",
        withdrawTxn.withdrawMemo,
        withdrawTxn.withdrawMemoType,
      )
    }
    Log.info("Transfer complete")
    waitStatus(withdraw.id, COMPLETED, wallet.sep6)

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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer!!.id, expectedCustomerStatuses)
  }

  @Test
  fun `test withdraw-exchange to contract account end-to-end`() = runBlocking {
    val wallet = WalletClient(CLIENT_SMART_WALLET_ACCOUNT, CLIENT_WALLET_SECRET, null, toml)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = wallet.sep12.putCustomer(customerRequest)!!

    val withdraw =
      wallet.sep6.withdraw(
        mapOf(
          "destination_asset" to "iso4217:CAD",
          "source_asset" to USDC.code,
          "amount" to "1",
          "type" to "bank_account",
        ),
        exchange = true,
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")

    val additionalRequiredFields =
      wallet.sep12
        .getCustomer(customer.id, "sep-6", withdraw.id)!!
        .fields
        ?.filter { it.key != null && it.value?.optional == false }
        ?.map { it.key!! }
        .orEmpty()
    val additionalCustomerRequest =
      gson.fromJson(
        gson.toJson(additionalRequiredFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    wallet.sep12.putCustomer(additionalCustomerRequest)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, wallet.sep6)

    val withdrawTxn = wallet.sep6.getTransaction(mapOf("id" to withdraw.id)).transaction
    transactionWithRetry {
      val txHash =
        wallet.send(
          withdrawTxn.withdrawAnchorAccount,
          Asset.create(USDC.id),
          "1",
          withdrawTxn.withdrawMemo,
          withdrawTxn.withdrawMemoType,
        )
      Log.info("Transfer complete: $txHash")
    }

    waitStatus(withdraw.id, COMPLETED, wallet.sep6)

    val completedWithdrawTxn = wallet.sep6.getTransaction(mapOf("id" to withdraw.id))
    val transactionByStellarId: GetTransactionResponse =
      wallet.sep6.getTransaction(
        mapOf("stellar_transaction_id" to completedWithdrawTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedWithdrawTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  private suspend fun assertWalletReceivedStatuses(
    txnId: String,
    expected: List<SepTransactionStatus>,
  ) {
    val callbacks =
      walletServerClient.pollTransactionCallbacks(
        "sep6",
        txnId,
        expected.size,
        GetTransactionResponse::class.java,
      )
    val statuses = callbacks.map { it.transaction.status }
    assertEquals(expected.map { it.status }, statuses)
  }

  private suspend fun assertWalletReceivedCustomerStatuses(
    id: String,
    expected: List<Sep12Status>,
  ) {
    val callbacks = walletServerClient.pollCustomerCallbacks(id, expected.size)
    val statuses: List<Sep12Status> = callbacks.map { it.status }
    assertEquals(expected, statuses)
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
