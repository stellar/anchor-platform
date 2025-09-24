package org.stellar.reference.wallet

import com.sksamuel.hoplite.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import java.io.File
import mu.KotlinLogging

val log = KotlinLogging.logger {}
lateinit var walletServer: NettyApplicationEngine

fun main(args: Array<String>) {
  startServer(null, args.getOrNull(0)?.toBooleanStrictOrNull() ?: true)
}

fun startServer(envMap: Map<String, String>?, wait: Boolean) {
  log.info { "Starting wallet reference server" }

  // read config
  val cfg = readCfg(envMap)

  // start server
  walletServer =
    embeddedServer(Netty, port = cfg.wallet.port) {
        install(ContentNegotiation) { json() }
        configureRouting(cfg)
        install(CORS) {
          anyHost()
          allowHeader(HttpHeaders.Authorization)
          allowHeader(HttpHeaders.ContentType)
        }
      }
      .start(wait)
}

fun readCfg(envMap: Map<String, String>?): Config {
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
  locationCfg.fold({}, { cfgBuilder.addFileSource(it.walletServerConfig) })
  // Add default config file as a property source.
  cfgBuilder.addResourceSource("/wallet-server-config.yaml")

  return cfgBuilder.build().loadConfigOrThrow<Config>()
}

fun stopServer() {
  log.info("Stopping wallet server...")
  if (::walletServer.isInitialized) (walletServer).stop(5000, 30000)
  log.info("Wallet server stopped...")
}

fun Application.configureRouting(cfg: Config) {
  routing { callback(cfg, CallbackService()) }
  routing { noncustodial(cfg) }
  routing { staticFiles("/.well-known", File(cfg.toml.path)) }
}
