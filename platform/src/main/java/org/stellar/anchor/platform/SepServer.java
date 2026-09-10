package org.stellar.anchor.platform;

import static org.springframework.boot.Banner.Mode.OFF;
import static org.stellar.anchor.util.Log.info;

import java.util.Map;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.stellar.anchor.platform.configurator.SecretManager;
import org.stellar.anchor.platform.configurator.SepConfigManager;

@SpringBootApplication
@EnableJpaRepositories(basePackages = {"org.stellar.anchor.platform.data"})
@EntityScan(basePackages = {"org.stellar.anchor.platform.data"})
@ComponentScan(
    basePackages = {
      "org.stellar.anchor.platform.controller.sep",
      "org.stellar.anchor.platform.component.sep",
      "org.stellar.anchor.platform.component.share"
    })
@EnableScheduling
@EnableConfigurationProperties
public class SepServer extends AbstractPlatformServer implements WebMvcConfigurer {
  private ConfigurableApplicationContext ctx;

  public ConfigurableApplicationContext start(Map<String, String> environment) {
    buildEnvironment(environment);

    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(SepServer.class).bannerMode(OFF);
    SpringApplication springApplication = builder.build();
    info("Adding secret manager as initializers...");
    springApplication.addInitializers(SecretManager.getInstance());
    info("Adding sep config manager as initializers...");
    springApplication.addInitializers(SepConfigManager.getInstance());

    // Bridge Tomcat's JUL logging to SLF4J
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();

    return ctx = springApplication.run();
  }
}
