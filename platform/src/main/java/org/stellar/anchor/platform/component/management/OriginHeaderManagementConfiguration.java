package org.stellar.anchor.platform.component.management;

import jakarta.servlet.Filter;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.stellar.anchor.platform.component.share.OriginHeaderBeans;

/**
 * Registers the {@code Origin} guard on the actuator port.
 *
 * <p>With {@code management.server.port} set, Spring Boot runs the actuator in a child context that
 * does not see the filters registered in the main one, and its preflight handling parses the {@code
 * Origin} header the same way. Boot loads this class through {@code
 * META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports}
 * and only into the child context ({@link ManagementContextType#CHILD}); when the actuator shares
 * the main port, {@link OriginHeaderBeans} already covers it. It sits outside the packages the
 * servers component-scan, as Boot requires for these classes.
 */
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
public class OriginHeaderManagementConfiguration {
  @Bean
  public FilterRegistrationBean<Filter> originHeaderFilter() {
    return OriginHeaderBeans.originHeaderRegistration();
  }
}
