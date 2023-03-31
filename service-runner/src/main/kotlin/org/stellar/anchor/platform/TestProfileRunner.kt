@file:JvmName("TestProfileRunner")

package org.stellar.anchor.platform

import com.palantir.docker.compose.DockerComposeExtension
import com.palantir.docker.compose.connection.waiting.HealthChecks
import java.io.File
import kotlinx.coroutines.*
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.stellar.anchor.util.Log.info

lateinit var testProfileExecutor: TestProfileExecutor

fun main() = runBlocking {
  info("Starting TestPfofileExecutor...")
  testProfileExecutor = TestProfileExecutor(TestConfig(profileName = "default"))

  GlobalScope.launch {
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
  private val docker: DockerComposeExtension
  private var runningServers: MutableList<ConfigurableApplicationContext> = mutableListOf()
  private var shouldStartDockerCompose: Boolean = false
  private var shouldStartServers: Boolean = false

  init {
    info("Initializing TestProfileRunner...")
    val dockerComposeFile = getResourceFile("docker-compose-test.yaml")
    val userHomeFolder = File(System.getProperty("user.home"))
    docker =
      DockerComposeExtension.builder()
        .saveLogsTo("${userHomeFolder}/docker-logs/anchor-platform-integration-test")
        .file("${dockerComposeFile.absolutePath}")
        .waitingForService("kafka", HealthChecks.toHaveAllPortsOpen())
        .waitingForService("db", HealthChecks.toHaveAllPortsOpen())
        .pullOnStartup(true)
        .build()
  }

  fun start(wait: Boolean = false, preStart: (config: TestConfig) -> Unit = {}) {
    preStart(this.config)

    shouldStartDockerCompose = config.env["run_docker"].toBoolean()
    shouldStartServers = config.env["run_servers"].toBoolean()

    info("Starting servers and docker")
    if (shouldStartDockerCompose) startDocker()
    if (shouldStartServers) startServers(wait)
  }

  fun shutdown() {
    if (shouldStartServers) shutdownServers()
    if (shouldStartDockerCompose) shutdownDocker()
  }

  private fun startServers(wait: Boolean): MutableList<ConfigurableApplicationContext> {
    runBlocking {
      val envMap = config.env

      envMap["assets.value"] = getResourceFile(envMap["assets.value"]!!).absolutePath
      envMap["sep1.toml.value"] = getResourceFile(envMap["sep1.toml.value"]!!).absolutePath

      // Start servers
      val jobs = mutableListOf<Job>()
      val scope = CoroutineScope(Dispatchers.Default)
      jobs += scope.launch { ServiceRunner.startKotlinReferenceServer(envMap, false) }
      jobs += scope.launch { runningServers.add(ServiceRunner.startAnchorReferenceServer()) }
      jobs += scope.launch { runningServers.add(ServiceRunner.startStellarObserver(envMap)) }
      jobs += scope.launch { runningServers.add(ServiceRunner.startSepServer(envMap)) }
      jobs.forEach { it.join() }

      if (wait) {
        while (true) {
          delay(60000)
        }
      }
    }
    return runningServers
  }

  private fun startDocker() {
    docker.beforeAll(null)
  }
  private fun shutdownServers() {
    runningServers.forEach { SpringApplication.exit(it) }
    org.stellar.reference.stop()
  }

  private fun shutdownDocker() {
    docker.afterAll(null)
  }
}
