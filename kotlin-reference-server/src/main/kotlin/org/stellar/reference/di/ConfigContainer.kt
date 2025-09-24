package org.stellar.reference.di

import com.sksamuel.hoplite.*
import org.stellar.reference.data.Config
import org.stellar.reference.data.LocationConfig

class ConfigContainer(envMap: Map<String, String>?) {
  var config: Config = readCfg(envMap)

  companion object {
    @Volatile private var instance: ConfigContainer? = null

    fun init(envMap: Map<String, String>?): ConfigContainer {
      return instance
        ?: synchronized(this) { instance ?: ConfigContainer(envMap).also { instance = it } }
    }

    fun getInstance(): ConfigContainer {
      return instance!!
    }

    private fun readCfg(envMap: Map<String, String>?): Config {
      // Load location config
      val locationCfg =
        ConfigLoaderBuilder.default()
          .addPropertySource(PropertySource.environment())
          .build()
          .loadConfig<LocationConfig>()

      val cfgBuilder = ConfigLoaderBuilder.default()
      // Add environment variables as a property source.
      cfgBuilder.addPropertySource(PropertySource.environment())
      envMap?.run { cfgBuilder.addMapSource(this) }
      // Add config file as a property source if valid
      locationCfg.fold({}, { cfgBuilder.addFileSource(it.ktReferenceServerConfig) })

      return cfgBuilder.build().loadConfigOrThrow<Config>()
    }
  }
}
