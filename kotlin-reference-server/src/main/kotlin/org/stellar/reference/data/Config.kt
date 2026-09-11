package org.stellar.reference.data

import com.sksamuel.hoplite.ConfigAlias

data class LocationConfig(val ktReferenceServerConfig: String?)

data class Config(
  @ConfigAlias("app") val appSettings: AppSettings,
  @ConfigAlias("auth") val authSettings: AuthSettings,
  @ConfigAlias("data") val dataSettings: DataSettings,
  val sep24: Sep24,
)

data class Sep24(val interactiveJwtKey: String)

data class AppSettings(
  val version: String,
  val isTest: Boolean,
  val port: Int,
  val horizonEndpoint: String,
  val rpcEndpoint: String,
  val platformApiEndpoint: String,
  val distributionWallet: String,
  val distributionWalletMemo: String,
  val distributionWalletMemoType: String,
  val rpcEnabled: Boolean,
  val enableTest: Boolean,
  val paymentSigningSeed: String,
  // Whether Sep31EventProcessor auto-advances a SEP-31 transaction in reaction to its own status-
  // change events (requesting/confirming KYC, and notifying offchain funds sent once external
  // funds are pending). Real anchor integrations rely on this; a suite that drives a transaction
  // through RPC calls itself (e.g. testing error/recovery) races against it, since both are trying
  // to advance the same transaction concurrently. Defaults to true to preserve existing behavior.
  val autoAdvanceSep31: Boolean = true,
)

data class AuthSettings(
  val type: Type,
  val platformToAnchorSecret: String,
  val anchorToPlatformSecret: String,
  val expirationMilliseconds: Long,
) {
  enum class Type {
    NONE,
    API_KEY,
    JWT,
  }
}

data class DataSettings(
  val url: String,
  val database: String,
  val user: String,
  val password: String,
)
