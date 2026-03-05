package org.stellar.anchor.sep1;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.MetricConstants.SEP1_TOML_ACCESSED;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.nio.file.Path;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.config.Sep1Config;
import org.stellar.anchor.util.FileUtil;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.NetUtil;

public class Sep1Service implements ISep1Service {
  private static final long MAX_TOML_SIZE = 100 * 1024;
  private final Sep1Config sep1Config;
  private String tomlValue = null;
  Counter sep1TomlAccessedCounter = Metrics.counter(SEP1_TOML_ACCESSED);

  /**
   * Construct the Sep1Service that reads the stellar.toml content from Java resource.
   *
   * @param sep1Config The Sep1 configuration.
   */
  public Sep1Service(Sep1Config sep1Config) {
    this.sep1Config = sep1Config;
    Log.info("Sep1Service initialized.");
  }

  @Override
  public String readSep1Toml(Sep1Config sep1Config) throws Exception {
    if (sep1Config.isEnabled()) {
      switch (sep1Config.getType()) {
        case STRING:
          debugF("reading stellar.toml from config[sep1.toml.value]");
          return sep1Config.getValue();
        case FILE:
          debugF("reading stellar.toml from {}", sep1Config.getValue());
          return readTomlFromFile(sep1Config.getValue());
        case URL:
          debugF("reading stellar.toml from {}", sep1Config.getValue());
          return readTomlFromURL(sep1Config.getValue());
        default:
          throw new InvalidConfigException(
              String.format("invalid sep1.type: %s", sep1Config.getType()));
      }
    } else {
      throw new SepException("SEP-1 is not enabled");
    }
  }

  public String getToml() throws SepException {
    try {
      if (tomlValue == null) tomlValue = readSep1Toml(sep1Config);
      sep1TomlAccessedCounter.increment();
      return tomlValue;
    } catch (SepException sepEx) {
      throw sepEx;
    } catch (Exception e) {
      Log.error("Failed to read stellar.toml", e);
      throw new SepException("Unable to read SEP-1 value");
    }
  }

  String readTomlFromFile(String path) throws IOException {
    return FileUtil.read(Path.of(path), MAX_TOML_SIZE);
  }

  String readTomlFromURL(String path) throws IOException {
    return NetUtil.fetch(path, MAX_TOML_SIZE);
  }
}
