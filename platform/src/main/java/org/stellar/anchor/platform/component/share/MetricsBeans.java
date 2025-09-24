package org.stellar.anchor.platform.component.share;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.config.MetricConfig;

@Configuration
public class MetricsBeans {
  /**********************************
   * Metric configurations
   */
  @Bean
  @ConfigurationProperties(prefix = "metrics")
  MetricConfig metricConfig() {
    return new MetricConfig();
  }

  @Bean
  MetricsService metricsService() {
    return new MetricsService();
  }

  @Bean
  public MeterFilter meterFilter(MetricConfig metricConfig) {
    return new MeterFilter() {
      @Override
      public Meter.Id map(Meter.Id id) {
        if (StringUtils.isEmpty(metricConfig.getPrefix())) {
          return id;
        }
        return id.withName(metricConfig.getPrefix() + id.getName());
      }
    };
  }
}
