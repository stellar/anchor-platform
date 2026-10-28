package org.stellar.anchor.platform.component.share;

import jakarta.servlet.Filter;
import lombok.SneakyThrows;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.platform.config.AppLoggingConfig;
import org.stellar.anchor.platform.config.SepServerConfig;
import org.stellar.anchor.platform.utils.ClientIpResolver;
import org.stellar.anchor.platform.utils.RequestLoggerFilter;
import org.stellar.anchor.platform.utils.TrustedProxies;

@Configuration
public class RequestLoggerBeans {
  @Bean
  @SneakyThrows
  public ClientIpResolver clientIpResolver(SepServerConfig sepServerConfig) {
    return new ClientIpResolver(TrustedProxies.parse(sepServerConfig.getTrustedProxies()));
  }

  @Bean
  public FilterRegistrationBean<Filter> requestLoggerFilter(
      AppLoggingConfig appLoggingConfig, ClientIpResolver clientIpResolver) {
    FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(new RequestLoggerFilter(appLoggingConfig, clientIpResolver));
    registrationBean.addUrlPatterns("/");
    registrationBean.addUrlPatterns("/*");
    return registrationBean;
  }
}
