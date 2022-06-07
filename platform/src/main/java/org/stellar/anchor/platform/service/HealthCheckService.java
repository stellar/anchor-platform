package org.stellar.anchor.platform.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.stellar.anchor.api.platform.HealthCheckResponse;
import org.stellar.anchor.healthcheck.HealthCheckProcessor;
import org.stellar.anchor.healthcheck.HealthCheckable;

@Service
public class HealthCheckService {
  HealthCheckProcessor processor;

  public HealthCheckService(List<HealthCheckable> checkables) {
    processor = new HealthCheckProcessor(checkables);
  }

  public HealthCheckResponse check(List<String> checks) {
    return processor.check(checks);
  }
}
