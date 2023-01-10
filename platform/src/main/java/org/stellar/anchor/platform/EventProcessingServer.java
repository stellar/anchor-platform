package org.stellar.anchor.platform;

import static org.springframework.boot.Banner.Mode.OFF;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.stellar.anchor.platform.configurator.EventProcessorConfigManager;
import org.stellar.anchor.platform.configurator.SecretManager;

@SpringBootApplication
@EnableJpaRepositories(basePackages = {"org.stellar.anchor.platform.data"})
@EntityScan(basePackages = {"org.stellar.anchor.platform.data"})
@ComponentScan(
    basePackages = {
      "org.stellar.anchor.platform.controller.eventprocessor",
      "org.stellar.anchor.platform.component.eventprocessor",
      "org.stellar.anchor.platform.component.share"
    })
@EnableConfigurationProperties
public class EventProcessingServer extends AbstractPlatformServer implements WebMvcConfigurer {
  public static ConfigurableApplicationContext start(Map<String, Object> environment) {
    buildEnvironment(environment);

    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(EventProcessingServer.class).bannerMode(OFF);
    SpringApplication springApplication = builder.build();
    springApplication.addInitializers(SecretManager.getInstance());
    springApplication.addInitializers(EventProcessorConfigManager.getInstance());

    return springApplication.run();
  }
}
