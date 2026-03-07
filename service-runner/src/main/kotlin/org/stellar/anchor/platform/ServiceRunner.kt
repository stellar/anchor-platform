package org.stellar.anchor.platform

import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.jetbrains.annotations.NotNull
import org.springframework.context.ConfigurableApplicationContext
import org.stellar.anchor.api.shared.Metadata
import org.stellar.anchor.platform.main as testProfileRunnerMain
import org.stellar.anchor.util.Log.errorEx
import org.stellar.anchor.util.Log.info
import org.stellar.reference.start as startReferenceServer
import org.stellar.reference.wallet.start as startWalletServerFunction

object ServiceRunner {
  @JvmStatic
  fun main(args: Array<String>) {
    printBanner()
    startServers(args)
  }

  private fun startServers(args: Array<String>) {
    val options = getOptions()
    try {
      val cmd = DefaultParser().parse(options, args)
      var anyServerStarted = false

      if (cmd.hasOption("sep-server") || cmd.hasOption("all")) {
        startSepServer(null)
        anyServerStarted = true
      }

      if (cmd.hasOption("custody-server") || cmd.hasOption("all")) {
        startCustodyServer(null)
        anyServerStarted = true
      }

      if (cmd.hasOption("platform-server") || cmd.hasOption("all")) {
        startPlatformServer(null)
        anyServerStarted = true
      }

      if (cmd.hasOption("stellar-observer") || cmd.hasOption("all")) {
        startStellarObserver(null)
        anyServerStarted = true
      }

      if (cmd.hasOption("event-processor") || cmd.hasOption("all")) {
        startEventProcessingServer(null)
        anyServerStarted = true
      }

      if (cmd.hasOption("kotlin-reference-server") || cmd.hasOption("all")) {
        startKotlinReferenceServer(null, true)
        anyServerStarted = true
      }

      if (cmd.hasOption("wallet-reference-server") || cmd.hasOption("all")) {
        startWalletServer(null, true)
        anyServerStarted = true
      }

      if (cmd.hasOption("test-profile-runner")) {
        startTestProfileRunner()
        anyServerStarted = true
      }

      if (!anyServerStarted) {
        printUsage(options)
      }
    } catch (e: ParseException) {
      printUsage(options)
    }
  }

  private fun printBanner() {
    println("****************************************")
    println("           Anchor Platform              ")
    println("           Version ${getVersion()}")
    println("****************************************")
  }

  @NotNull
  private fun getOptions(): Options {
    val options = Options()
    options.addOption("h", "help", false, "Print this message.")
    options.addOption("a", "all", false, "Start all servers.")
    options.addOption("s", "sep-server", false, "Start SEP endpoint server.")
    options.addOption("c", "custody-server", false, "Start Custody server.")
    options.addOption("p", "platform-server", false, "Start Platform API endpoint server.")
    options.addOption(
      "o",
      "stellar-observer",
      false,
      "Start Observer that streams from the Stellar blockchain."
    )
    options.addOption("e", "event-processor", false, "Start the event processor.")
    options.addOption("k", "kotlin-reference-server", false, "Start Kotlin reference server.")
    options.addOption("w", "wallet-reference-server", false, "Start wallet reference server.")
    options.addOption("t", "test-profile-runner", false, "Run the stack with test profile.")
    return options
  }

  @JvmStatic
  fun startSepServer(env: Map<String, String>?): ConfigurableApplicationContext {
    info("Starting SEP server...")
    return SepServer().start(env)
  }

  @JvmStatic
  fun startPlatformServer(env: Map<String, String>?): ConfigurableApplicationContext {
    info("Starting platform server...")
    return PlatformServer().start(env)
  }

  @JvmStatic
  fun startCustodyServer(env: Map<String, String>?): ConfigurableApplicationContext {
    info("Starting custody server...")
    return CustodyServer().start(env)
  }

  @JvmStatic
  fun startStellarObserver(env: Map<String, String>?): ConfigurableApplicationContext {
    info("Starting observer...")
    return StellarObservingServer().start(env)
  }

  @JvmStatic
  fun startEventProcessingServer(env: Map<String, String>?): ConfigurableApplicationContext {
    info("Starting event processing server...")
    return EventProcessingServer().start(env)
  }

  @JvmStatic
  fun startKotlinReferenceServer(envMap: Map<String, String>?, wait: Boolean) {
    try {
      info("Starting Kotlin reference server...")
      startReferenceServer(envMap, wait)
    } catch (e: Exception) {
      errorEx("Error starting reference server", e)
      throw e
    }
  }

  @JvmStatic
  fun startWalletServer(envMap: Map<String, String>?, wait: Boolean) {
    try {
      info("Starting wallet server...")
      startWalletServerFunction(envMap, wait)
    } catch (e: Exception) {
      errorEx("Error starting wallet server", e)
      throw e
    }
  }

  @JvmStatic
  fun startTestProfileRunner() {
    info("Running test profile runner")
    testProfileRunnerMain()
  }

  @JvmStatic
  fun printUsage(options: Options) {
    val helper = HelpFormatter()
    helper.optionComparator = null
    helper.printHelp("java -jar anchor-platform.jar", options)
  }

  @JvmStatic
  fun getVersion(): String {
    return Metadata.getVersion()
  }
}
