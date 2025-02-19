package org.stellar.anchor.platform.e2etest

import io.ktor.http.*
import java.math.BigInteger
import java.net.URI
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
import org.stellar.anchor.api.sep.sep45.ChallengeRequest
import org.stellar.anchor.api.sep.sep6.GetTransactionResponse
import org.stellar.anchor.api.shared.InstructionField
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep45Client
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.CLIENT_SMART_WALLET_ACCOUNT
import org.stellar.anchor.platform.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log
import org.stellar.reference.wallet.WalletServerClient
import org.stellar.sdk.*
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.Auth.authorizeEntry
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.SorobanAuthorizationEntry
import org.stellar.walletsdk.anchor.MemoType
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.anchor.customer
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.horizon.sign

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class Sep6End2EndTest : AbstractIntegrationTests(TestConfig()) {
  private val maxTries = 30
  private val walletServerClient = WalletServerClient(Url(config.env["wallet.server.url"]!!))
  private val rpc = SorobanServer("https://soroban-testnet.stellar.org")
  private val gson = GsonUtils.getInstance()

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
    val customer =
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
    val webAuthDomain = toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT")
    val homeDomain = "http://${URI.create(webAuthDomain).authority}"

    val sep45Client =
      Sep45Client(toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT"), rpc, CLIENT_WALLET_SECRET)
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    val token = sep45Client.validate(sep45Client.sign(challenge)).token
    val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token)
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = sep12Client.putCustomer(customerRequest)!!

    val deposit =
      sep6Client.deposit(
        mapOf(
          "asset_code" to USDC.code,
          "account" to CLIENT_SMART_WALLET_ACCOUNT,
          "amount" to "1",
          "type" to "SWIFT",
        )
      )
    Log.info("Deposit initiated: ${deposit.id}")

    val additionalRequiredFields =
      sep12Client
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
    sep12Client.putCustomer(additionalCustomerRequest)

    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, sep6Client)

    val completedDepositTxn = sep6Client.getTransaction(mapOf("id" to deposit.id))
    val transactionByStellarId: GetTransactionResponse =
      sep6Client.getTransaction(
        mapOf("stellar_transaction_id" to completedDepositTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedDepositTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  @Test
  fun `test typical deposit-exchange without quote end-to-end flow`() = runBlocking {
    val memo = (20000..30000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    val customer =
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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer.id, expectedCustomerStatuses)
  }

  @Test
  fun `test typical deposit-exchange to contract account end-to-end`() = runBlocking {
    val webAuthDomain = toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT")
    val homeDomain = "http://${URI.create(webAuthDomain).authority}"

    val sep45Client =
      Sep45Client(toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT"), rpc, CLIENT_WALLET_SECRET)
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    val token = sep45Client.validate(sep45Client.sign(challenge)).token
    val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token)
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = sep12Client.putCustomer(customerRequest)!!

    val deposit =
      sep6Client.deposit(
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
      sep12Client
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
    sep12Client.putCustomer(additionalCustomerRequest)

    Log.info("Bank transfer complete")
    waitStatus(deposit.id, COMPLETED, sep6Client)

    val completedDepositTxn = sep6Client.getTransaction(mapOf("id" to deposit.id))
    val transactionByStellarId: GetTransactionResponse =
      sep6Client.getTransaction(
        mapOf("stellar_transaction_id" to completedDepositTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedDepositTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  @Test
  fun `test typical withdraw end-to-end flow`() = runBlocking {
    val memo = (40000..50000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    // TODO: migrate this to wallet-sdk when it's available
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    val customer =
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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer.id, expectedCustomerStatuses)
  }

  @Test
  fun `test typical withdraw to contract account end-to-end`() = runBlocking {
    val webAuthDomain = toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT")
    val homeDomain = "http://${URI.create(webAuthDomain).authority}"

    val sep45Client =
      Sep45Client(toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT"), rpc, CLIENT_WALLET_SECRET)
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    val token = sep45Client.validate(sep45Client.sign(challenge)).token
    val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token)
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = sep12Client.putCustomer(customerRequest)!!

    val withdraw =
      sep6Client.withdraw(
        mapOf("asset_code" to USDC.code, "amount" to "1", "type" to "bank_account")
      )
    Log.info("Withdrawal initiated: ${withdraw.id}")

    val additionalRequiredFields =
      sep12Client
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
    sep12Client.putCustomer(additionalCustomerRequest)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, sep6Client)

    val withdrawTxn = sep6Client.getTransaction(mapOf("id" to withdraw.id)).transaction

    val txHash =
      transferFunds(
        CLIENT_SMART_WALLET_ACCOUNT,
        withdrawTxn.withdrawAnchorAccount,
        Asset.create(USDC.id),
        "1",
        walletKeyPair.keyPair,
      )
    Log.info("Transfer complete: $txHash")

    waitStatus(withdraw.id, COMPLETED, sep6Client)

    val completedWithdrawTxn = sep6Client.getTransaction(mapOf("id" to withdraw.id))
    val transactionByStellarId: GetTransactionResponse =
      sep6Client.getTransaction(
        mapOf("stellar_transaction_id" to completedWithdrawTxn.transaction.stellarTransactionId)
      )
    assertEquals(completedWithdrawTxn.transaction.id, transactionByStellarId.transaction.id)
  }

  @Test
  fun `test typical withdraw-exchange without quote end-to-end flow`() = runBlocking {
    val memo = (50000..60000).random().toULong()
    val token = anchor.auth().authenticate(walletKeyPair, memoId = memo)
    // TODO: migrate this to wallet-sdk when it's available
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token.token)

    // Create a customer before starting the transaction
    val customer =
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

    val expectedCustomerStatuses =
      listOf(
        Sep12Status.ACCEPTED, // initial customer status before SEP-6 transaction
        Sep12Status.NEEDS_INFO, // SEP-6 transaction requires additional info
        Sep12Status.ACCEPTED, // additional info provided
      )
    assertWalletReceivedCustomerStatuses(customer.id, expectedCustomerStatuses)
  }

  @Test
  fun `test withdraw-exchange to contract account end-to-end`() = runBlocking {
    val webAuthDomain = toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT")
    val homeDomain = "http://${URI.create(webAuthDomain).authority}"

    val sep45Client =
      Sep45Client(toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT"), rpc, CLIENT_WALLET_SECRET)
    val challenge =
      sep45Client.getChallenge(
        ChallengeRequest.builder()
          .account(CLIENT_SMART_WALLET_ACCOUNT)
          .homeDomain(homeDomain)
          .build()
      )
    val token = sep45Client.validate(sep45Client.sign(challenge)).token
    val sep12Client = Sep12Client(toml.getString("KYC_SERVER"), token)
    val sep6Client = Sep6Client("${config.env["anchor.domain"]}/sep6", token)

    val customerRequest =
      gson.fromJson(
        gson.toJson(basicInfoFields.associateWith { customerInfo[it]!! }),
        Sep12PutCustomerRequest::class.java,
      )
    val customer = sep12Client.putCustomer(customerRequest)!!

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

    val additionalRequiredFields =
      sep12Client
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
    sep12Client.putCustomer(additionalCustomerRequest)
    Log.info("Submitted additional KYC info: $additionalRequiredFields")

    waitStatus(withdraw.id, PENDING_USR_TRANSFER_START, sep6Client)

    val withdrawTxn = sep6Client.getTransaction(mapOf("id" to withdraw.id)).transaction

    val txHash =
      transferFunds(
        CLIENT_SMART_WALLET_ACCOUNT,
        withdrawTxn.withdrawAnchorAccount,
        Asset.create(USDC.id),
        "1",
        walletKeyPair.keyPair,
      )
    Log.info("Transfer complete: $txHash")

    waitStatus(withdraw.id, COMPLETED, sep6Client)

    val completedWithdrawTxn = sep6Client.getTransaction(mapOf("id" to withdraw.id))
    val transactionByStellarId: GetTransactionResponse =
      sep6Client.getTransaction(
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

  private fun transferFunds(
    source: String,
    destination: String,
    asset: Asset,
    amount: String,
    signer: KeyPair,
  ): String {
    val parameters =
      mutableListOf(
        // from=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(source).address)
          .build(),
        // to=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(destination).address)
          .build(),
        SCVal.builder()
          .discriminant(SCValType.SCV_I128)
          .i128(Scv.toInt128(BigInteger.valueOf(amount.toLong() * 10000000)).i128)
          .build(),
      )
    val operation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(Network.TESTNET),
          "transfer",
          parameters,
        )
        .build()

    var account = rpc.getAccount(walletKeyPair.keyPair.accountId)
    val transaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(operation)
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val simulationResponse = rpc.simulateTransaction(transaction)
    val signedAuthEntries = mutableListOf<SorobanAuthorizationEntry>()
    simulationResponse.results.forEach {
      it.auth.forEach { entryXdr ->
        val entry = SorobanAuthorizationEntry.fromXdrBase64(entryXdr)
        val validUntilLedgerSeq = simulationResponse.latestLedger + 10

        val signedEntry = authorizeEntry(entry, signer, validUntilLedgerSeq, Network.TESTNET)
        signedAuthEntries.add(signedEntry)
      }
    }

    val signedOperation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(Network.TESTNET),
          "transfer",
          parameters,
        )
        .sourceAccount(walletKeyPair.keyPair.accountId)
        .auth(signedAuthEntries)
        .build()

    account = rpc.getAccount(walletKeyPair.keyPair.accountId)
    val authorizedTransaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(signedOperation)
        .setBaseFee(Transaction.MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val preparedTransaction = rpc.prepareTransaction(authorizedTransaction)
    preparedTransaction.sign(signer)

    val transactionResponse = rpc.sendTransaction(preparedTransaction)

    return transactionResponse.hash
  }
}
