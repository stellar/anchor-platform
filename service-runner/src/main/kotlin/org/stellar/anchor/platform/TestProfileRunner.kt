@file:JvmName("TestProfileRunner")

package org.stellar.anchor.platform

import com.palantir.docker.compose.DockerComposeExtension
import com.palantir.docker.compose.configuration.ProjectName
import com.palantir.docker.compose.connection.waiting.HealthChecks
import java.io.File
import java.lang.Thread.sleep
import java.util.*
import kotlinx.coroutines.*
import org.springframework.context.ConfigurableApplicationContext
import org.stellar.anchor.util.Log.info

const val RUN_DOCKER = "run.docker"
const val RUN_ALL_SERVERS = "run.all.servers"
const val RUN_SEP_SERVER = "run.sep.server"
const val RUN_PLATFORM_SERVER = "run.platform.server"
const val RUN_EVENT_PROCESSING_SERVER = "run.event.processing.server"
const val RUN_PAYMENT_OBSERVER = "run.observer"
const val RUN_CUSTODY_SERVER = "run.custody.server"
const val RUN_KOTLIN_REFERENCE_SERVER = "run.kotlin.reference.server"
const val RUN_WALLET_SERVER = "run.wallet.server"
const val WALLET_SECRET_KEY = "wallet.secret.key"

lateinit var testProfileExecutor: TestProfileExecutor

fun main() = runBlocking {
  info("Starting TestPfofileExecutor...")
  testProfileExecutor = TestProfileExecutor(TestConfig())

  launch {
    Runtime.getRuntime()
      .addShutdownHook(
        object : Thread() {
          override fun run() {
            testProfileExecutor.shutdown()
          }
        }
      )
  }

  testProfileExecutor.start(true)
}

class TestProfileExecutor(val config: TestConfig) {
  private lateinit var docker: DockerComposeExtension
  private var runningServers: MutableList<ConfigurableApplicationContext> = mutableListOf()
  private var shouldStartDockerCompose: Boolean = false
  private var shouldStartAllServers: Boolean = false
  private var shouldStartSepServer: Boolean = false
  private var shouldStartPlatformServer: Boolean = false
  private var shouldStartWalletServer: Boolean = false
  private var shouldStartObserver: Boolean = false
  private var shouldStartCustodyServer: Boolean = false
  private var shouldStartEventProcessingServer: Boolean = false
  private var shouldStartKotlinReferenceServer: Boolean = false
  private var custodyEnabled: Boolean = false

  fun start(wait: Boolean = false, preStart: (config: TestConfig) -> Unit = {}) {
    info("Starting TestProfileExecutor...")

    preStart(this.config)

    shouldStartDockerCompose = config.env[RUN_DOCKER].toBoolean()
    shouldStartAllServers = config.env[RUN_ALL_SERVERS].toBoolean()

    shouldStartSepServer = config.env[RUN_SEP_SERVER].toBoolean()
    shouldStartPlatformServer = config.env[RUN_PLATFORM_SERVER].toBoolean()
    shouldStartObserver = config.env[RUN_PAYMENT_OBSERVER].toBoolean()
    shouldStartCustodyServer = config.env[RUN_CUSTODY_SERVER].toBoolean()
    shouldStartEventProcessingServer = config.env[RUN_EVENT_PROCESSING_SERVER].toBoolean()
    shouldStartKotlinReferenceServer = config.env[RUN_KOTLIN_REFERENCE_SERVER].toBoolean()
    shouldStartWalletServer = config.env[RUN_WALLET_SERVER].toBoolean()

    val custodyType = config.env["custody.type"]
    if (custodyType != null) {
      custodyEnabled = "none" != custodyType
    }

    startDocker()
    startServers(wait)
  }

  fun shutdown() {
    shutdownServers()
    if (shouldStartDockerCompose) shutdownDocker()
  }

  private fun startServers(wait: Boolean): MutableList<ConfigurableApplicationContext> {
    runBlocking {
      val envMap = config.env

      envMap["assets.value"] = getResourceFile(envMap["assets.value"]!!).absolutePath
      if (envMap["clients.value"] != null) {
        // If clients.value not found, try to load it from resources
        if (!File(envMap["clients.value"]!!).exists()) {
          envMap["clients.value"] = getResourceFile(envMap["clients.value"]!!).absolutePath
        }
      }
      if (envMap["sep1.toml.type"] != "url" && envMap["sep1.toml.type"] != "string") {
        // If sep1.toml.value not found, try to load it from resources
        if (!File(envMap["sep1.toml.value"]!!).exists()) {
          envMap["sep1.toml.value"] = getResourceFile(envMap["sep1.toml.value"]!!).absolutePath
        }
      }
      // Start servers
      val jobs = mutableListOf<Job>()
      val scope = CoroutineScope(Dispatchers.Default)

      if (shouldStartAllServers || shouldStartKotlinReferenceServer) {
        jobs += scope.launch { ServiceRunner.startKotlinReferenceServer(envMap, wait) }
      }
      if (shouldStartAllServers || shouldStartWalletServer) {
        jobs += scope.launch { ServiceRunner.startWalletServer(envMap, wait) }
      }
      if ((shouldStartAllServers || shouldStartObserver) && !custodyEnabled) {
        jobs += scope.launch { runningServers.add(ServiceRunner.startStellarObserver(envMap)) }
      }
      if ((shouldStartAllServers || shouldStartCustodyServer) && custodyEnabled) {
        jobs += scope.launch { runningServers.add(ServiceRunner.startCustodyServer(envMap)) }
      }
      if (shouldStartAllServers || shouldStartEventProcessingServer) {
        jobs +=
          scope.launch { runningServers.add(ServiceRunner.startEventProcessingServer(envMap)) }
      }
      if (shouldStartAllServers || shouldStartSepServer) {
        jobs += scope.launch { runningServers.add(ServiceRunner.startSepServer(envMap)) }
      }
      if (shouldStartAllServers || shouldStartPlatformServer) {
        jobs += scope.launch { runningServers.add(ServiceRunner.startPlatformServer(envMap)) }
      }

      if (jobs.size > 0) {
        jobs.forEach { it.join() }

        if (wait)
          do {
            val anyActive = runningServers.any { it.isActive || it.isRunning }
            delay(5000)
          } while (anyActive)
      }
    }

    return runningServers
  }

  private fun startDocker() {
    if (shouldStartDockerCompose) {
      info("Starting docker compose...")
      val dockerComposeFile = getResourceFile("docker-compose-test.yaml")
      val userHomeFolder = File(System.getProperty("user.home"))
      if (isWindows()) {
        System.getenv("DOCKER_LOCATION")
          ?: throw RuntimeException("DOCKER_LOCATION env variable is not set")
        System.getenv("DOCKER_COMPOSE_LOCATION")
          ?: throw RuntimeException("DOCKER_COMPOSE_LOCATION env variable is not set")
      }
      docker =
        DockerComposeExtension.builder()
          .saveLogsTo("${userHomeFolder}/docker-logs/anchor-platform-integration-test")
          .file(dockerComposeFile.absolutePath)
          .waitingForService("kafka", HealthChecks.toHaveAllPortsOpen())
          .waitingForService("db", HealthChecks.toHaveAllPortsOpen())
          .pullOnStartup(true)
          .projectName(
            ProjectName.fromString("anchorplatform${UUID.randomUUID().toString().takeLast(6)}")
          )
          .build()

      docker.beforeAll(null)
    }
  }

  private fun shutdownServers() {
    runningServers.forEach {
      it.close()
      it.stop()
    }

    runningServers.forEach {
      while (it.isRunning || it.isActive) {
        sleep(1000)
      }
    }

    if (shouldStartAllServers || shouldStartKotlinReferenceServer) org.stellar.reference.stop()
    if (shouldStartAllServers || shouldStartWalletServer) org.stellar.reference.wallet.stop()
  }

  private fun shutdownDocker() {
    docker.afterAll(null)
  }

  private fun isWindows(): Boolean {
    return System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
  }
}
