package org.stellar.anchor.platform

import java.io.File
import java.math.BigDecimal
import java.net.URL
import org.stellar.sdk.*
import org.stellar.sdk.Network.TESTNET
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.PaymentOperation

/**
 * Generates ephemeral Stellar testnet accounts for CI runs, so each run uses fresh accounts and
 * tests can run in parallel without concurrency conflicts.
 */
object EphemeralAccountSetup {

  private const val FRIENDBOT_URL = "https://friendbot.stellar.org/?addr="
  private const val HORIZON_URL = "https://horizon-testnet.stellar.org"
  private const val USDC_ISSUER = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"

  data class AccountSet(
    val clientWallet: KeyPair,
    val extraSigner1: KeyPair,
    val extraSigner2: KeyPair,
    val withdrawFund1: KeyPair,
    val withdrawFund2: KeyPair,
    val depositFund1: KeyPair,
    val depositFund2: KeyPair,
    val distribution: KeyPair,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val usdcIssuerSecret =
      System.getenv("TEST_USDC_ISSUER_SECRET")
        ?: throw IllegalStateException("TEST_USDC_ISSUER_SECRET not set")

    val outputPath = args.firstOrNull() ?: "/tmp/ephemeral-accounts.env"
    val accounts = generateAccounts()
    val server = Server(HORIZON_URL)
    val usdc = Asset.createNonNativeAsset("USDC", USDC_ISSUER)
    val issuerKeyPair = KeyPair.fromSecretSeed(usdcIssuerSecret)

    // Fund accounts via friendbot (all except extra signers — they only sign off-chain)
    val accountsToFund =
      listOf(
        accounts.clientWallet,
        accounts.withdrawFund1,
        accounts.withdrawFund2,
        accounts.depositFund1,
        accounts.depositFund2,
        accounts.distribution,
      )

    for (kp in accountsToFund) {
      fundViaFriendbot(kp)
    }

    // Create USDC trustlines: distribution, client wallet, withdraw fund 1, deposit fund 1
    val accountsNeedingTrustline =
      listOf(
        accounts.distribution,
        accounts.clientWallet,
        accounts.withdrawFund1,
        accounts.depositFund1,
      )

    for (kp in accountsNeedingTrustline) {
      createTrustline(server, kp, usdc)
    }

    // Mint USDC from issuer to distribution (1000 USDC) and withdraw fund 1 (10 USDC)
    mintUsdc(server, issuerKeyPair, accounts.distribution, usdc, BigDecimal("1000"))
    mintUsdc(server, issuerKeyPair, accounts.withdrawFund1, usdc, BigDecimal("10"))

    // Write env file
    val envContent = buildEnvFile(accounts)
    File(outputPath).writeText(envContent)
    println("Ephemeral accounts written to $outputPath")
  }

  private fun generateAccounts(): AccountSet {
    return AccountSet(
      clientWallet = KeyPair.random(),
      extraSigner1 = KeyPair.random(),
      extraSigner2 = KeyPair.random(),
      withdrawFund1 = KeyPair.random(),
      withdrawFund2 = KeyPair.random(),
      depositFund1 = KeyPair.random(),
      depositFund2 = KeyPair.random(),
      distribution = KeyPair.random(),
    )
  }

  private fun fundViaFriendbot(kp: KeyPair) {
    val maxRetries = 3
    for (attempt in 1..maxRetries) {
      try {
        URL("$FRIENDBOT_URL${kp.accountId}").openStream().close()
        println("Funded ${kp.accountId}")
        if (attempt < maxRetries) Thread.sleep(1000)
        return
      } catch (e: Exception) {
        if (attempt == maxRetries) throw RuntimeException("Failed to fund ${kp.accountId}", e)
        println("Friendbot attempt $attempt failed for ${kp.accountId}, retrying...")
        Thread.sleep(2000)
      }
    }
  }

  private fun createTrustline(server: Server, kp: KeyPair, asset: Asset) {
    val account = server.accounts().account(kp.accountId)
    val tx =
      TransactionBuilder(Account(kp.accountId, account.sequenceNumber), TESTNET)
        .setBaseFee(AbstractTransaction.MIN_BASE_FEE)
        .setTimeout(30)
        .addOperation(
          ChangeTrustOperation.builder()
            .asset(ChangeTrustAsset(asset))
            .limit(BigDecimal("922337203685.4775807"))
            .build()
        )
        .build()
    tx.sign(kp)
    server.submitTransaction(tx)
    println("Trustline created for ${kp.accountId}")
  }

  private fun mintUsdc(
    server: Server,
    issuer: KeyPair,
    destination: KeyPair,
    asset: Asset,
    amount: BigDecimal,
  ) {
    val issuerAccount = server.accounts().account(issuer.accountId)
    val tx =
      TransactionBuilder(Account(issuer.accountId, issuerAccount.sequenceNumber), TESTNET)
        .setBaseFee(AbstractTransaction.MIN_BASE_FEE)
        .setTimeout(30)
        .addOperation(
          PaymentOperation.builder()
            .destination(destination.accountId)
            .asset(asset)
            .amount(amount)
            .build()
        )
        .build()
    tx.sign(issuer)
    server.submitTransaction(tx)
    println("Minted $amount USDC to ${destination.accountId}")
  }

  private fun buildEnvFile(accounts: AccountSet): String {
    return buildString {
      appendLine("TEST_CLIENT_WALLET_SECRET=${String(accounts.clientWallet.secretSeed)}")
      appendLine(
        "TEST_CLIENT_WALLET_EXTRA_SIGNER_1_SECRET=${String(accounts.extraSigner1.secretSeed)}"
      )
      appendLine(
        "TEST_CLIENT_WALLET_EXTRA_SIGNER_2_SECRET=${String(accounts.extraSigner2.secretSeed)}"
      )
      appendLine("TEST_WITHDRAW_FUND_CLIENT_SECRET_1=${String(accounts.withdrawFund1.secretSeed)}")
      appendLine("TEST_WITHDRAW_FUND_CLIENT_SECRET_2=${String(accounts.withdrawFund2.secretSeed)}")
      appendLine("TEST_DEPOSIT_FUND_CLIENT_SECRET_1=${String(accounts.depositFund1.secretSeed)}")
      appendLine("TEST_DEPOSIT_FUND_CLIENT_SECRET_2=${String(accounts.depositFund2.secretSeed)}")
      appendLine("APP__PAYMENT_SIGNING_SEED=${String(accounts.distribution.secretSeed)}")
      appendLine("CLIENT_WALLET_PUBKEY=${accounts.clientWallet.accountId}")
      appendLine("DISTRIBUTION_ACCOUNT_PUBKEY=${accounts.distribution.accountId}")
    }
  }
}
