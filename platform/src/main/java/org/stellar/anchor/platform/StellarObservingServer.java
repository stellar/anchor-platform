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
import org.stellar.anchor.platform.configurator.ObserverConfigManager;
import org.stellar.anchor.platform.configurator.SecretManager;

@Profile("stellar-observer")
@SpringBootApplication
@EnableJpaRepositories(basePackages = {"org.stellar.anchor.platform.data"})
@EntityScan(basePackages = {"org.stellar.anchor.platform.data"})
@EnableConfigurationProperties
public class StellarObservingServer implements WebMvcConfigurer {
  public static ConfigurableApplicationContext start(Map<String, Object> environment) {
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(StellarObservingServer.class)
            .bannerMode(OFF)
            .properties(
                // this allows a developer to use a .env file for local development
                "spring.profiles.active=stellar-observer");

    if (environment != null) {
      builder.properties(environment);
    }

    SpringApplication springApplication = builder.build();

    springApplication.addInitializers(SecretManager.getInstance());
    springApplication.addInitializers(ObserverConfigManager.getInstance());

    return springApplication.run();
  }
}
