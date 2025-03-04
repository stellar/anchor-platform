package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.info;

import java.util.List;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ConfigurableApplicationContext;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class CustodyConfigManager extends ConfigManager {
  private static final CustodyConfigManager custodyConfigManager = new CustodyConfigManager();

  private CustodyConfigManager() {}

  public static CustodyConfigManager getInstance() {
    return custodyConfigManager;
  }

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
        List.of(
            new SentryConfigAdapter(),
            new LogConfigAdapter(),
            new DataConfigAdapter(),
            new CustodyServerConfigAdapter()));
  }
}

class CustodyServerConfigAdapter extends SpringConfigAdapter {
  @Override
  void updateSpringEnv(ConfigMap config) throws InvalidConfigException {
    copy(config, "custody_server.context_path", "server.servlet.context-path");
    copy(config, "custody_server.port", "server.port");
    set("spring.mvc.converters.preferred-json-mapper", "gson");
    if (config.getBoolean("metrics.enabled")) {
      set("management.endpoints.enabled-by-default", true);
      set("management.endpoints.web.exposure.include", "health,info,prometheus");
    } else {
      set("management.endpoints.enabled-by-default", false);
    }
  }

  @Override
  void validate(ConfigMap config) throws InvalidConfigException {
    // noop
  }
}
