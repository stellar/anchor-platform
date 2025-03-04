package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.StringHelper.*;

import io.sentry.Sentry;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class SentryConfigAdapter extends SpringConfigAdapter {
  @Override
  void updateSpringEnv(ConfigMap config) throws InvalidConfigException {
    String dsn = get("sentry.dsn");
    String sentryEnv = get("sentry.environment");
    String sentryDebug = get("sentry.debug");
    if (isNotEmpty(dsn)) {
      info("Sentry DSN is set. Initializing Sentry...");
      Sentry.init(
          options -> {
            options.setDsn(dsn);
            if (isNotEmpty(sentryEnv)) {
              options.setEnvironment(sentryEnv);
            }
            if (isNotEmpty(sentryDebug)) {
              options.setDebug(Boolean.parseBoolean(sentryDebug));
            }
            options.setSendDefaultPii(true);
            options.setTracesSampleRate(1.0);
            options.setEnableUncaughtExceptionHandler(true);
          });
    }
  }

  @Override
  void validate(ConfigMap config) throws InvalidConfigException {
    String dsn = get("sentry.dsn");
    if (isNotEmpty(dsn)) {
      if (isEmpty(System.getenv("SENTRY_AUTH_TOKEN"))) {
        throw new InvalidConfigException(
            "Please set SENTRY_AUTH_TOKEN when [sentry.dsn] is defined.");
      }
    }
  }
}
