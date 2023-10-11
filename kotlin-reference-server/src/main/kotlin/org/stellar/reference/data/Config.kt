package org.stellar.reference.data

import com.sksamuel.hoplite.ConfigAlias
import org.stellar.sdk.KeyPair

data class LocationConfig(val ktReferenceServerConfig: String)

data class Config(
  @ConfigAlias("app") val appSettings: AppSettings,
  @ConfigAlias("auth") val authSettings: AuthSettings,
  @ConfigAlias("event") val eventSettings: EventSettings,
  val sep24: Sep24
)

data class Sep24(val secret: String, val interactiveJwtKey: String) {
  val keyPair = KeyPair.fromSecretSeed(secret)
}

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
  val secret: String
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

data class EventSettings(val enabled: Boolean, val bootstrapServer: String, val topic: String)
