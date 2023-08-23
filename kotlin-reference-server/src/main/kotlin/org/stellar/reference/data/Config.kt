package org.stellar.reference.data

import com.sksamuel.hoplite.ConfigAlias
import org.stellar.sdk.KeyPair

data class LocationConfig(val ktReferenceServerConfig: String)

data class Config(
  @ConfigAlias("app") val appSettings: AppSettings,
  @ConfigAlias("auth") val authSettings: AuthSettings,
  val sep24: Sep24
)

data class Sep24(val enableTest: Boolean, val secret: String, val interactiveJwtKey: String) {
  val keyPair = KeyPair.fromSecretSeed(secret)
}

data class AppSettings(
  val version: String,
  val port: Int,
  val horizonEndpoint: String,
  val platformApiEndpoint: String,
  val distributionWallet: String,
  val distributionWalletMemo: String,
  val distributionWalletMemoType: String,
)

data class AuthSettings(
  val type: Type,
  val platformToAnchorSecret: String,
  val anchorToPlatformSecret: String,
  val expirationMilliseconds: Long
) {
  enum class Type {
    NONE,
    API_KEY,
    JWT
  }
}
