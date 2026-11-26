package org.stellar.anchor.platform.component.share;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.stellar.anchor.platform.utils.OriginHeaderFilter;

/**
 * Registers the {@code Origin} guard for every server that scans this package (SEP, Platform,
 * Observer and Event processor), ahead of Spring MVC's CORS processing and of the token filters.
 */
@Configuration
public class OriginHeaderBeans {
  @Bean
  public FilterRegistrationBean<Filter> originHeaderFilter() {
    FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
    registrationBean.setFilter(new OriginHeaderFilter());
    registrationBean.addUrlPatterns("/*");
    registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registrationBean;
  }
}
