package org.stellar.reference.di

import com.sksamuel.hoplite.*
import org.stellar.reference.data.Config

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
      val cfgBuilder = ConfigLoaderBuilder.default()
      // Add environment variables as a property source.
      cfgBuilder.addPropertySource(PropertySource.environment())
      envMap?.run {
        cfgBuilder.addMapSource(this)
        if (envMap[KT_REFERENCE_SERVER_CONFIG] != null) {
          cfgBuilder.addFileSource(envMap[KT_REFERENCE_SERVER_CONFIG]!!)
        }
      }
      return cfgBuilder.build().loadConfigOrThrow<Config>()
    }
  }
}
