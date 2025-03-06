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
  val platformApiEndpoint: String,
  val distributionWallet: String,
  val distributionWalletMemo: String,
  val distributionWalletMemoType: String,
  val custodyEnabled: Boolean,
  val rpcEnabled: Boolean,
  val enableTest: Boolean,
  val secret: String,
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
