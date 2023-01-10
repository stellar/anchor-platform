package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.info;

import java.util.List;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ConfigurableApplicationContext;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class EventProcessorConfigManager extends ConfigManager {
  private static final EventProcessorConfigManager configManager =
      new EventProcessorConfigManager();

  private EventProcessorConfigManager() {}

  public static EventProcessorConfigManager getInstance() {
    return configManager;
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
            new LogConfigAdapter(), new DataConfigAdapter(), new EventProcessorConfigAdapter()));
  }
}

class EventProcessorConfigAdapter extends SpringConfigAdapter {
  @Override
  void updateSpringEnv(ConfigMap config) throws InvalidConfigException {
    copy(config, "event_processor.context_path", "server.servlet.context-path");
    copy(config, "event_processor.port", "server.port");
    set("spring.mvc.converters.preferred-json-mapper", "gson");
  }
}
