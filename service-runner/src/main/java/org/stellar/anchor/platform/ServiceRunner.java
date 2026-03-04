package org.stellar.anchor.platform;

import static org.stellar.anchor.util.Log.*;

import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ConfigurableApplicationContext;
import org.stellar.anchor.api.shared.Metadata;
import org.stellar.reference.ReferenceServerStartKt;
import org.stellar.reference.wallet.WalletServerStartKt;

public class ServiceRunner {
  public static void main(String[] args) {
    printBanner();
    startServers(args);
  }

  private static void startServers(String[] args) {
    Options options = getOptions();
    try {
      CommandLine cmd = new DefaultParser().parse(options, args);
      boolean anyServerStarted = false;
      if (cmd.hasOption("sep-server") || cmd.hasOption("all")) {
        startSepServer(null);
        anyServerStarted = true;
      }

      if (cmd.hasOption("platform-server") || cmd.hasOption("all")) {
        startPlatformServer(null);
        anyServerStarted = true;
      }

      if (cmd.hasOption("stellar-observer") || cmd.hasOption("all")) {
        startStellarObserver(null);
        anyServerStarted = true;
      }

      if (cmd.hasOption("event-processor") || cmd.hasOption("all")) {
        startEventProcessingServer(null);
        anyServerStarted = true;
      }

      if (cmd.hasOption("kotlin-reference-server") || cmd.hasOption("all")) {
        startKotlinReferenceServer(null, true);
        anyServerStarted = true;
      }

      if (cmd.hasOption("wallet-reference-server") || cmd.hasOption("all")) {
        startWalletServer(null, true);
        anyServerStarted = true;
      }

      if (cmd.hasOption("test-profile-runner")) {
        startTestProfileRunner();
        anyServerStarted = true;
      }

      if (!anyServerStarted) {
        printUsage(options);
      }
    } catch (ParseException e) {
      printUsage(options);
    }
  }

  private static void printBanner() {
    System.out.println("****************************************");
    System.out.println("           Anchor Platform              ");
    System.out.println("           Version " + getVersion());
    System.out.println("****************************************");
  }

  @NotNull
  private static Options getOptions() {
    Options options = new Options();
    options.addOption("h", "help", false, "Print this message.");
    options.addOption("a", "all", false, "Start all servers.");
    options.addOption("s", "sep-server", false, "Start SEP endpoint server.");
    options.addOption("p", "platform-server", false, "Start Platform API endpoint server.");
    options.addOption(
        "o", "stellar-observer", false, "Start Observer that streams from the Stellar blockchain.");
    options.addOption("e", "event-processor", false, "Start the event processor.");
    options.addOption("k", "kotlin-reference-server", false, "Start Kotlin reference server.");
    options.addOption("w", "wallet-reference-server", false, "Start wallet reference server.");
    options.addOption("t", "test-profile-runner", false, "Run the stack with test profile.");
    return options;
  }

  public static ConfigurableApplicationContext startSepServer(Map<String, String> env) {
    info("Starting SEP server...");
    return new SepServer().start(env);
  }

  public static ConfigurableApplicationContext startPlatformServer(Map<String, String> env) {
    info("Starting platform server...");
    return new PlatformServer().start(env);
  }

  public static ConfigurableApplicationContext startStellarObserver(Map<String, String> env) {
    info("Starting observer...");
    return new StellarObservingServer().start(env);
  }

  public static ConfigurableApplicationContext startEventProcessingServer(Map<String, String> env) {
    info("Starting event processing server...");
    return new EventProcessingServer().start(env);
  }

  public static void startKotlinReferenceServer(Map<String, String> envMap, boolean wait) {
    try {
      info("Starting Kotlin reference server...");
      ReferenceServerStartKt.start(envMap, wait);
    } catch (Exception e) {
      errorEx("Error starting reference server", e);
      throw e;
    }
  }

  public static void startWalletServer(Map<String, String> envMap, boolean wait) {
    try {
      info("Starting wallet server...");
      WalletServerStartKt.start(envMap, wait);
    } catch (Exception e) {
      errorEx("Error starting wallet server", e);
      throw e;
    }
  }

  public static void startTestProfileRunner() {
    info("Running test profile runner");
    TestProfileRunner.main();
  }

  static void printUsage(Options options) {
    HelpFormatter helper = new HelpFormatter();
    helper.setOptionComparator(null);
    helper.printHelp("java -jar anchor-platform.jar", options);
  }

  static String getVersion() {
    return Metadata.getVersion();
  }
}
