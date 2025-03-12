package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.StringHelper.*;

import io.sentry.Sentry;
import org.stellar.anchor.api.exception.InvalidConfigException;

public class SentryConfigAdapter extends SpringConfigAdapter {
  @Override
  void updateSpringEnv(ConfigMap config) throws InvalidConfigException {
    String dsn = config.getString("sentry.dsn");
    String sentryEnv = config.getString("sentry.environment");
    String authToken = getAuthToken();
    if (isNotEmpty(dsn) && isNotEmpty(authToken)) {
      info("sentry.dsn and SENTRY_AUTH_TOKEN are set. Initializing Sentry agent...");
      initSentry(config, dsn, sentryEnv);
    }
  }

  void initSentry(ConfigMap config, String dsn, String sentryEnv) {
    Sentry.init(
        options -> {
          options.setDsn(dsn);
          if (isNotEmpty(sentryEnv)) {
            options.setEnvironment(sentryEnv);
          }
          options.setRelease(config.getString("sentry.release"));
          options.setDebug(config.getBoolean("sentry.debug"));
          options.setTracesSampleRate(1.0);
          options.setEnableUncaughtExceptionHandler(true);
        });
  }

  String getAuthToken() {
    return System.getenv("SENTRY_AUTH_TOKEN");
  }

  @Override
  void validate(ConfigMap config) throws InvalidConfigException {
    String dsn = config.getString("sentry.dsn");
    if (isNotEmpty(dsn)) {
      if (isEmpty(getAuthToken())) {
        throw new InvalidConfigException(
            "Please set SENTRY_AUTH_TOKEN when [sentry.dsn] is defined.");
      }
      if (isEmpty(config.getString("sentry.release"))) {
        throw new InvalidConfigException(
            "Please set [sentry.release] when [sentry.dsn] is defined.");
      }
      if (isEmpty(config.getString("sentry.environment"))) {
        throw new InvalidConfigException(
            "Please set [sentry.environment] when [sentry.dsn] is defined.");
      }
    }
  }
}
