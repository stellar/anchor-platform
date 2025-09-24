package org.stellar.reference.wallet

data class LocationConfig(val walletServerConfig: String)

data class Config(
  val wallet: WalletConfig,
  val anchor: AnchorConfig,
  val secret: SecretConfig,
  val toml: TomlConfig
)

data class AnchorConfig(val domain: String, val endpoint: String)

data class WalletConfig(val port: Int, val hostname: String)

data class SecretConfig(val key: String)

data class TomlConfig(val path: String)
