package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.api.platform.HealthCheckStatus.GREEN;
import static org.stellar.anchor.platform.configurator.ConfigMap.ConfigSource.FILE;
import static org.stellar.anchor.util.Log.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.Resource;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.platform.HealthCheckResult;
import org.stellar.anchor.api.platform.HealthCheckStatus;
import org.stellar.anchor.healthcheck.HealthCheckable;

public abstract class ConfigManager
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, HealthCheckable {

  static final String STELLAR_ANCHOR_CONFIG = "STELLAR_ANCHOR_CONFIG";
  static ConfigManager configManager;

  ConfigMap configMap;

  ConfigManager() {}

  public static ConfigManager getInstance() {
    if (configManager == null) configManager = new DefaultConfigManager();
    return configManager;
  }

  public ConfigMap getConfigMap() {
    return configMap;
  }

  /**
   * Check if any of the security sensitive configuration values are present in the configuration.
   *
   * @param configMap the configuration map
   */
  void sanitize(ConfigMap configMap) {
    SecretManager.getInstance()
        .secretVars
        .forEach(
            var -> {
              if (configMap.get(var) != null) {
                warnF(
                    "Possible secret leak of config[{}]. Please remove the secret from configuration.",
                    var);
                configMap.remove(var);
              }
            });
  }

  static final String CONFIG_NAME = "config";

  void sendToSpring(
      ConfigurableApplicationContext applicationContext,
      ConfigMap config,
      List<SpringConfigAdapter> adapters)
      throws InvalidConfigException {

    Properties props = new Properties();
    props.putAll(config.toStringMap());

    // Set Platform configurations
    PropertiesPropertySource pps = new PropertiesPropertySource(CONFIG_NAME, props);
    applicationContext.getEnvironment().getPropertySources().addFirst(pps);

    for (SpringConfigAdapter adapter : adapters) {
      try {
        adapter.validate(config);
      } catch (InvalidConfigException icex) {
        errorF(
            "Invalid configuration. {}{} Reason={}{}",
            System.lineSeparator(),
            System.lineSeparator(),
            icex.getMessage(),
            System.lineSeparator());
        // We should not continue.
        throw new RuntimeException(icex);
      }
      adapter.updateSpringEnv(applicationContext, config);
    }
  }

  ConfigMap processConfigurations(ConfigurableApplicationContext applicationContext)
      throws IOException, InvalidConfigException {
    info("reading default configuration values");
    // Load default values
    ConfigMap latestConfig = ConfigHelper.loadDefaultConfig();

    infoF("default configuration version={}", latestConfig.getVersion());
    // Check if default config is consistent with the definition
    ConfigReader configReader = new ConfigReader(latestConfig.getVersion());
    info("validating default configuration values");
    configReader.validate(latestConfig);

    // Read the configuration from the YAML file specified by the STELLAR_ANCHOR_CONFIG system
    // environment variable.
    Resource configFileResource = getConfigFileAsResource(applicationContext);
    if (configFileResource != null) {
      infoF("reading configuration file from {}", configFileResource.getURL());
      ConfigMap yamlConfig = ConfigHelper.loadConfig(configFileResource, FILE);
      latestConfig.merge(updateToLatestConfig(latestConfig, yamlConfig));
    }

    // Read and process the environment variable
    ConfigMap envConfig = ConfigHelper.loadConfigFromEnv(latestConfig.getVersion());
    if (envConfig != null) {
      info("Processing system environment variables");
      latestConfig.merge(updateToLatestConfig(latestConfig, envConfig));
    }

    return latestConfig;
  }

  ConfigMap updateToLatestConfig(ConfigMap latestConfig, ConfigMap config)
      throws InvalidConfigException {
    int configVersion = config.getVersion();
    int latestVersion = latestConfig.getVersion();

    ConfigReader configReader = new ConfigReader(configVersion);
    configReader.validate(config);

    for (int version = configVersion + 1; version <= latestVersion; version++) {
      infoF("Applying configuration version {}", version);
      configReader = new ConfigReader(version);
      // readFrom(config) returns a new config object to avoid mutating the config map argument
      config = configReader.readFrom(config);
    }

    return config;
  }

  Resource getConfigFileAsResource(ConfigurableApplicationContext applicationContext)
      throws IOException {
    String yamlConfigFile = ConfigEnvironment.getenv(STELLAR_ANCHOR_CONFIG);

    if (yamlConfigFile != null) {
      List<String> yamlConfigFiles =
          Arrays.asList(
              yamlConfigFile,
              String.format("file://%s", yamlConfigFile), // For Linux file systems
              String.format("file:%s", yamlConfigFile)); // For Windows file systems
      Resource resource = null;
      for (String file : yamlConfigFiles) {
        resource = applicationContext.getResource(file);
        if (resource.exists()) {
          return resource;
        }
      }
      if (!resource.exists()) {
        throw new IOException(
            String.format("Failed to read configuration from %s", yamlConfigFile));
      }
      return resource;
    }
    return null;
  }

  @Override
  public String getName() {
    return "config";
  }

  @Override
  public List<Tags> getTags() {
    return List.of(Tags.ALL, Tags.CONFIG);
  }

  @Override
  public HealthCheckResult check() {
    return ConfigManagerHealthCheckResult.builder()
        .name(getName())
        .configured(configMap.names())
        .build();
  }

  @Override
  public int compareTo(@NotNull HealthCheckable other) {
    return this.getName().compareTo(other.getName());
  }
}

@Data
@Builder
class ConfigManagerHealthCheckResult implements HealthCheckResult {
  transient String name;

  Collection<String> configured;

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<HealthCheckStatus> getStatuses() {
    return List.of();
  }

  @Override
  public HealthCheckStatus getStatus() {
    return GREEN;
  }
}

class DefaultConfigManager extends ConfigManager {

  @SneakyThrows
  @Override
  public void initialize(@NotNull ConfigurableApplicationContext applicationContext) {
    // Read configuration from system environment variables, configuration file, and default values
    info("Read and process configurations");
    configMap = processConfigurations(applicationContext);

    // Make sure no secret is leaked.
    sanitize(configMap);

    // Send values to Spring
    sendToSpring(
        applicationContext,
        configMap,
        List.of(new SentryConfigAdapter(), new LogConfigAdapter(), new DataConfigAdapter()));
  }
}
