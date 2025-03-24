package org.stellar.anchor.platform

import io.github.cdimascio.dotenv.Dotenv

fun load(env: Dotenv, key: String): String {
  return System.getenv(key)
    ?: env[key] ?: throw IllegalStateException("$key not set in environment or .env file")
}

object TestSecrets {
  private val env: Dotenv = Dotenv.configure().directory("../").ignoreIfMissing().load()

  val SEP10_SIGNING_SEED = load(env, "SECRET_SEP10_SIGNING_SEED")
  val CLIENT_DOMAIN_SECRET = load(env, "SECRET__KEY") // This is the wallet server's SIGNING_KEY
  val CLIENT_WALLET_SECRET = load(env, "TEST_CLIENT_WALLET_SECRET")
  val CLIENT_SMART_WALLET_ACCOUNT = load(env, "TEST_CLIENT_SMART_WALLET_ACCOUNT")
  val CLIENT_WALLET_EXTRA_SIGNER_1_SECRET = load(env, "TEST_CLIENT_WALLET_EXTRA_SIGNER_1_SECRET")
  val CLIENT_WALLET_EXTRA_SIGNER_2_SECRET = load(env, "TEST_CLIENT_WALLET_EXTRA_SIGNER_2_SECRET")
  val WITHDRAW_FUND_CLIENT_SECRET_1 = load(env, "TEST_WITHDRAW_FUND_CLIENT_SECRET_1")
  val WITHDRAW_FUND_CLIENT_SECRET_2 = load(env, "TEST_WITHDRAW_FUND_CLIENT_SECRET_2")
  val DEPOSIT_FUND_CLIENT_SECRET_1 = load(env, "TEST_DEPOSIT_FUND_CLIENT_SECRET_1")
  val DEPOSIT_FUND_CLIENT_SECRET_2 = load(env, "TEST_DEPOSIT_FUND_CLIENT_SECRET_2")
}
