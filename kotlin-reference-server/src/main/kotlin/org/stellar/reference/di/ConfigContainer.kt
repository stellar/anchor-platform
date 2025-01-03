package org.stellar.reference.di

import com.sksamuel.hoplite.*
import org.stellar.anchor.util.StringHelper.dotToCamelCase
import org.stellar.reference.data.Config
import org.stellar.reference.data.LocationConfig

class ConfigContainer(envMap: Map<String, String>?) {
  var config: Config = readCfg(envMap)
  companion object {
    const val KT_REFERENCE_SERVER_CONFIG = "kt.reference.server.config"

    @Volatile private var instance: ConfigContainer? = null

    fun init(envMap: Map<String, String>?): ConfigContainer {
      return instance
        ?: synchronized(this) { instance ?: ConfigContainer(envMap).also { instance = it } }
    }

    fun getInstance(): ConfigContainer {
      return instance!!
    }

    private fun readCfg(envMap: Map<String, String>?): Config {
      // The location of the config file is determined by the environment variable first
      val locationCfgBuilder =
        ConfigLoaderBuilder.default().addPropertySource(PropertySource.environment())

      // Add environment variables as a property source for the config object
      val cfgBuilder = ConfigLoaderBuilder.default().addPropertySource(PropertySource.environment())

      // Add any environment variable overrides from the envMap
      envMap?.run {
        val camelEnvMap = this.mapKeys { (key, _) -> dotToCamelCase(key) }
        locationCfgBuilder.addMapSource(camelEnvMap)
        cfgBuilder.addMapSource(camelEnvMap)
      }

      val locationConfig = locationCfgBuilder.build().loadConfigOrThrow<LocationConfig>()
      if (locationConfig.ktReferenceServerConfig != null) {
        cfgBuilder.addFileSource(locationConfig.ktReferenceServerConfig)
      }
      return cfgBuilder.build().loadConfigOrThrow<Config>()
    }
  }
}
