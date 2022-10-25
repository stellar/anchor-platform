package org.stellar.anchor.platform;

import static org.springframework.boot.Banner.Mode.OFF;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.stellar.anchor.platform.configurator.ConfigManager;
import org.stellar.anchor.platform.configurator.SecretManager;

@Profile("stellar-observer")
@SpringBootApplication
@EnableJpaRepositories(basePackages = {"org.stellar.anchor.platform.data"})
@EntityScan(basePackages = {"org.stellar.anchor.platform.data"})
@EnableConfigurationProperties
public class StellarObservingService implements WebMvcConfigurer {

  public static ConfigurableApplicationContext start(
      int port, String contextPath, Map<String, Object> environment) {
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(StellarObservingService.class)
            .bannerMode(OFF)
            .properties(
                // TODO: update when the ticket
                // https://github.com/stellar/java-stellar-anchor-sdk/issues/297 is completed.
                "spring.mvc.converters.preferred-json-mapper=gson",
                // this allows a developer to use a .env file for local development
                "spring.config.import=optional:classpath:example.env[.properties]",
                "spring.profiles.active=stellar-observer",
                String.format("server.port=%d", port),
                String.format("server.contextPath=%s", contextPath));

    if (environment != null) {
      builder.properties(environment);
    }

    SpringApplication springApplication = builder.build();

    springApplication.addInitializers(SecretManager.getInstance());
    springApplication.addInitializers(ConfigManager.getInstance());
    return springApplication.run();
  }

  public static ConfigurableApplicationContext start(int port, String contextPath) {
    return start(port, contextPath, null);
  }
}
